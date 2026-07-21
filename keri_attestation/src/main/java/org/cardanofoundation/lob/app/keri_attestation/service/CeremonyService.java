package org.cardanofoundation.lob.app.keri_attestation.service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.ConsumedAttestation;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.CeremonyView;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.RequiredSteps;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;

/**
 * The ceremony state machine (design §4.2). Every transition takes the row lock via
 * {@link KeriAttestationCeremonyRepository#findByIdForUpdate(String)} so a retry bumping
 * {@code attemptGeneration} and a late async step-completion reading the pre-bump generation can
 * never interleave — the CAS in {@link #completeStep} and {@link #failStep} is only race-free because
 * the read-modify-write happens under that lock, inside this class's (default) {@code @Transactional}
 * boundary.
 *
 * <p>This class is the only place that knows the full ceremony API; other modules are only handed
 * {@link AttestationConsumptionApi}, which exposes exactly {@link #validateAndConsume} (design §4.6).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CeremonyService implements AttestationConsumptionApi {

    /** No longer counts against a user's active-ceremony limit and can never transition again. */
    private static final Set<CeremonyState> TERMINAL_STATES =
            EnumSet.of(CeremonyState.CONSUMED, CeremonyState.FAILED, CeremonyState.EXPIRED);

    /**
     * The "resting" states {@link #advanceToLinkDerivedFloor} is allowed to move a ceremony out of
     * (F1 fix, design §4.2): exactly the states {@link #fastForwardState} can itself produce as a
     * ceremony's initial state at {@link #create(String, String, String) create} time. A ceremony
     * actively waiting on a step
     * ({@code CREDENTIAL_REQUESTED}, {@code AUTH_BEGIN_SUBMITTED}, {@code ATTEST_REQUESTED}) must
     * never be silently fast-forwarded out from under its own in-flight worker; terminal states and
     * {@code ATTEST_ANCHORED} are not link-derived at all and must never move here either.
     */
    private static final Set<CeremonyState> LINK_ADVANCEABLE_STATES =
            EnumSet.of(CeremonyState.CREATED, CeremonyState.OOBI_RESOLVED, CeremonyState.CREDENTIAL_RECEIVED);

    private final KeriAttestationCeremonyRepository ceremonyRepository;
    private final KeriIdentityLinkRepository identityLinkRepository;
    private final KeriAttestationProperties properties;
    private final AttestationTargetProviderRegistry targetProviderRegistry;

    /**
     * Fast-forwards the initial state from the caller's identity link (design §4.2): a ceremony never
     * re-asks for something the user has already done at the identity level. {@code bindingVersion} is
     * captured from the link at creation time so a later relink can invalidate this ceremony
     * ({@link #validateAndConsume} checks it — design §4.7).
     */
    public Either<ProblemDetail, CeremonyView> create(String userId, String targetType, String targetId) {
        // Unlocked read-then-write: two concurrent create() calls for the same user can both pass this
        // check before either inserts, so the limit can be briefly exceeded by one. Accepted (design
        // §4.2) — not worth a row lock on every create for a soft per-user cap.
        long activeCount = ceremonyRepository.countByUserIdAndStateNotIn(userId, TERMINAL_STATES);
        if (activeCount >= properties.limits().maxActiveCeremoniesPerUser()) {
            return Either.left(KeriAttestationProblems.conflict(KeriAttestationProblems.CEREMONY_LIMIT_REACHED,
                    "User %s already has %d active ceremonies, the maximum allowed.".formatted(userId, activeCount)));
        }

        // Target authorization (F2 fix, design §3.3): a ceremony must never be created for a target the
        // caller cannot publish, or a target type nothing in the application knows how to attest.
        Optional<AttestationTargetProvider> providerOpt = targetProviderRegistry.forType(targetType);
        if (providerOpt.isEmpty()) {
            return Either.left(KeriAttestationProblems.unprocessable(KeriAttestationProblems.TARGET_MISMATCH,
                    "No provider for target type %s.".formatted(targetType)));
        }
        Optional<ProblemDetail> authFailure = providerOpt.get().authorize(targetId, userId);
        if (authFailure.isPresent()) {
            return Either.left(authFailure.get());
        }

        Optional<KeriIdentityLinkEntity> linkOpt = identityLinkRepository.findById(userId);
        int bindingVersion = linkOpt.map(KeriIdentityLinkEntity::getBindingVersion).orElse(0);
        CeremonyState initialState = fastForwardState(linkOpt);

        LocalDateTime now = LocalDateTime.now();
        KeriAttestationCeremonyEntity ceremony = new KeriAttestationCeremonyEntity();
        ceremony.setId(UUID.randomUUID().toString());
        ceremony.setUserId(userId);
        ceremony.setBindingVersion(bindingVersion);
        ceremony.setTargetType(targetType);
        ceremony.setTargetId(targetId);
        ceremony.setState(initialState);
        ceremony.setAttemptGeneration(0);
        ceremony.setExpiresAt(now.plus(properties.ceremonyTtl()));
        ceremonyRepository.save(ceremony);

        String authBeginTxHash = linkOpt.map(KeriIdentityLinkEntity::getAuthBeginTxHash).orElse(null);
        return Either.right(toView(ceremony, authBeginTxHash));
    }

    public Either<ProblemDetail, CeremonyView> get(String ceremonyId, String userId) {
        Optional<KeriAttestationCeremonyEntity> found = ceremonyRepository.findByIdForUpdate(ceremonyId);
        if (found.isEmpty()) {
            return Either.left(notFoundProblem(ceremonyId));
        }
        KeriAttestationCeremonyEntity ceremony = found.get();
        if (!ceremony.getUserId().equals(userId)) {
            return Either.left(forbiddenProblem(ceremonyId));
        }

        // Link-derived fast-forward (F1 fix, design §4.2 "completing an identity-level step advances
        // any open ceremony automatically"): a ceremony resting at CREATED/OOBI_RESOLVED/
        // CREDENTIAL_RECEIVED must reflect identity-level progress made after it was created.
        advanceToLinkDerivedFloor(ceremony);

        // Lazy expiry (design §4.2): a read reports/persists EXPIRED rather than erroring — the
        // caller asked "what's the state of this ceremony" and EXPIRED is a perfectly good answer.
        lazilyExpireIfNeeded(ceremony);

        String authBeginTxHash = identityLinkRepository.findById(userId)
                .map(KeriIdentityLinkEntity::getAuthBeginTxHash)
                .orElse(null);
        return Either.right(toView(ceremony, authBeginTxHash));
    }

    /**
     * Begins (or retries) a step. Non-retry requires the ceremony to be exactly in
     * {@code expectedState} and moves it to {@code waitingState}. Retry requires the ceremony to
     * already be in {@code waitingState} (you can only retry the step you are currently waiting on),
     * enforces {@code stepCooldown} against {@code updatedAt}, and bumps {@code attemptGeneration} so
     * that a late completion/failure from the superseded attempt is discarded by {@link #completeStep}
     * / {@link #failStep}'s generation check.
     */
    public Either<ProblemDetail, KeriAttestationCeremonyEntity> beginStep(String ceremonyId, String userId,
            CeremonyState expectedState, CeremonyState waitingState, boolean retry) {
        Optional<KeriAttestationCeremonyEntity> found = ceremonyRepository.findByIdForUpdate(ceremonyId);
        if (found.isEmpty()) {
            return Either.left(notFoundProblem(ceremonyId));
        }
        KeriAttestationCeremonyEntity ceremony = found.get();
        if (!ceremony.getUserId().equals(userId)) {
            return Either.left(forbiddenProblem(ceremonyId));
        }
        if (lazilyExpireIfNeeded(ceremony)) {
            return Either.left(expiredProblem(ceremonyId));
        }

        // Link-derived fast-forward (F1 fix, design §4.2), under the row lock and before the
        // expected-state check below: a ceremony created before an identity-level step (e.g. OOBI
        // resolve) completed must not stay stuck at its stale initial state forever once that step
        // finishes — this is what lets, e.g., credential/request succeed right after oobi/resolve
        // without the caller having re-polled GET first.
        advanceToLinkDerivedFloor(ceremony);

        LocalDateTime now = LocalDateTime.now();
        if (retry) {
            if (ceremony.getState() != waitingState) {
                return Either.left(invalidStateProblem(ceremonyId, waitingState, ceremony.getState()));
            }
            LocalDateTime cooldownEnds = ceremony.getUpdatedAt().plus(properties.limits().stepCooldown());
            if (now.isBefore(cooldownEnds)) {
                return Either.left(KeriAttestationProblems.conflict(KeriAttestationProblems.STEP_COOLDOWN,
                        "Ceremony %s may not retry before %s.".formatted(ceremonyId, cooldownEnds)));
            }
            ceremony.setAttemptGeneration(ceremony.getAttemptGeneration() + 1);
        } else {
            if (ceremony.getState() != expectedState) {
                return Either.left(invalidStateProblem(ceremonyId, expectedState, ceremony.getState()));
            }
            ceremony.setState(waitingState);
        }
        ceremony.setUpdatedAt(now);
        ceremonyRepository.save(ceremony);
        return Either.right(ceremony);
    }

    /**
     * CAS step completion: applies {@code mutator} and moves {@code from -> to} only if the ceremony
     * is still at generation {@code expectedGeneration} and state {@code from}. A superseded worker
     * (its step was retried, or the ceremony moved on for some other reason) silently no-ops instead
     * of corrupting newer state — there is no way to report failure back to it, by design.
     *
     * @return {@code true} if the CAS matched and the transition (and mutator) actually ran,
     *         {@code false} if this call was a stale no-op. Callers that do something <em>after</em>
     *         a successful completion which must never happen for a discarded, superseded attempt —
     *         e.g. {@link KeriNotificationCorrelator#markAndDelete} — must gate that on this return
     *         value: claiming/deleting a wallet notification on behalf of a call that the CAS just
     *         discarded could delete a signal a concurrent (winning) attempt still needs.
     */
    public boolean completeStep(String ceremonyId, int expectedGeneration, CeremonyState from, CeremonyState to,
            Consumer<KeriAttestationCeremonyEntity> mutator) {
        Optional<KeriAttestationCeremonyEntity> found = ceremonyRepository.findByIdForUpdate(ceremonyId);
        if (found.isEmpty()) {
            return false;
        }
        KeriAttestationCeremonyEntity ceremony = found.get();
        if (ceremony.getAttemptGeneration() != expectedGeneration || ceremony.getState() != from) {
            return false;
        }
        mutator.accept(ceremony);
        ceremony.setState(to);
        ceremony.setUpdatedAt(LocalDateTime.now());
        ceremonyRepository.save(ceremony);
        return true;
    }

    /**
     * Guarded update of step-data fields on a ceremony that is still waiting on the same step (F2 fix):
     * row-locks the ceremony, verifies it is still at generation {@code expectedGeneration} and state
     * {@code expectedWaitingState}, applies {@code mutator}, persists, and reports {@code true}. A
     * mismatch (a concurrent retry bumped the generation, or a concurrent completion/failure/sweep moved
     * the ceremony out of {@code expectedWaitingState}) leaves the row untouched and reports
     * {@code false} — exactly {@link #completeStep}/{@link #failStep}'s own CAS discipline, just without
     * a state transition of its own.
     *
     * <p>This exists because services were persisting intermediate step-data fields (e.g.
     * {@code requestExnSaid}, {@code metadataDigest}/{@code metadataLabel}, {@code authBeginTxHash}) by
     * saving the detached entity {@link #beginStep} returned, well after that call's own row lock was
     * released — a concurrent retry or sweep transition landing in between could be silently overwritten
     * by that later, unguarded save (state/generation resurrection). Routing every such write through
     * this method instead means it can never observe or clobber a ceremony that has since moved on.
     *
     * <p>{@code mutator} must only touch step-data fields (never {@code state} or
     * {@code attemptGeneration} — this method does not transition the ceremony, callers that need a
     * transition use {@link #completeStep}/{@link #failStep} instead) and must not itself be the source
     * of truth for whether the write happened: callers whose flow cannot proceed on a {@code false}
     * return must treat it like a stale worker — abandon silently in async paths (mirrors
     * {@link #completeStep}'s "no way to report failure back to it" contract), or return
     * {@code Either.left(CEREMONY_INVALID_STATE)} in synchronous paths.
     *
     * @return {@code true} if the guard matched and the mutator ran and was persisted, {@code false} if
     *         this call was a stale no-op.
     */
    public boolean updateWaitingStepData(String ceremonyId, int expectedGeneration, CeremonyState expectedWaitingState,
            Consumer<KeriAttestationCeremonyEntity> mutator) {
        Optional<KeriAttestationCeremonyEntity> found = ceremonyRepository.findByIdForUpdate(ceremonyId);
        if (found.isEmpty()) {
            return false;
        }
        KeriAttestationCeremonyEntity ceremony = found.get();
        if (ceremony.getAttemptGeneration() != expectedGeneration || ceremony.getState() != expectedWaitingState) {
            return false;
        }
        mutator.accept(ceremony);
        ceremony.setUpdatedAt(LocalDateTime.now());
        ceremonyRepository.save(ceremony);
        return true;
    }

    /**
     * CAS step failure: same generation-and-state guard as {@link #completeStep} — the ceremony must
     * still be at generation {@code expectedGeneration} <em>and</em> in {@code expectedWaitingState}, or
     * this silently no-ops. A generation-only check is not safe here: {@code attemptGeneration} only
     * bumps on retry, so a late failure signal for a step that already completed (generation unchanged)
     * would otherwise pass the CAS and clobber whatever later step the ceremony has since moved on to.
     */
    public void failStep(String ceremonyId, int expectedGeneration, CeremonyState expectedWaitingState,
            String errorTitle, String errorDetail) {
        Optional<KeriAttestationCeremonyEntity> found = ceremonyRepository.findByIdForUpdate(ceremonyId);
        if (found.isEmpty()) {
            return;
        }
        KeriAttestationCeremonyEntity ceremony = found.get();
        if (ceremony.getAttemptGeneration() != expectedGeneration || ceremony.getState() != expectedWaitingState) {
            return;
        }
        ceremony.setErrorTitle(errorTitle);
        ceremony.setErrorDetail(errorDetail);
        ceremony.setState(CeremonyState.FAILED);
        ceremony.setUpdatedAt(LocalDateTime.now());
        ceremonyRepository.save(ceremony);
    }

    /**
     * The sole entry point other modules use (design §4.6). Guard order matches the design exactly:
     * existence, ownership, target match, ceremony state, expiry, then the binding-version check that
     * catches a relink that happened after this ceremony was created.
     */
    @Override
    public Either<ProblemDetail, ConsumedAttestation> validateAndConsume(String ceremonyId, String targetType,
            String targetId, String userId) {
        Optional<KeriAttestationCeremonyEntity> found = ceremonyRepository.findByIdForUpdate(ceremonyId);
        if (found.isEmpty()) {
            return Either.left(notFoundProblem(ceremonyId));
        }
        KeriAttestationCeremonyEntity ceremony = found.get();
        if (!ceremony.getUserId().equals(userId)) {
            return Either.left(forbiddenProblem(ceremonyId));
        }
        if (!ceremony.getTargetType().equals(targetType) || !ceremony.getTargetId().equals(targetId)) {
            return Either.left(KeriAttestationProblems.unprocessable(KeriAttestationProblems.TARGET_MISMATCH,
                    "Ceremony %s was created for %s/%s, not %s/%s.".formatted(ceremonyId,
                            ceremony.getTargetType(), ceremony.getTargetId(), targetType, targetId)));
        }
        if (ceremony.getState() != CeremonyState.ATTEST_ANCHORED) {
            return Either.left(invalidStateProblem(ceremonyId, CeremonyState.ATTEST_ANCHORED, ceremony.getState()));
        }
        // ATTEST_ANCHORED is never a terminal state, so this always falls through to the same
        // expiry-mutate-and-persist behavior the inline check used to spell out directly.
        if (lazilyExpireIfNeeded(ceremony)) {
            return Either.left(expiredProblem(ceremonyId));
        }

        // Reaching ATTEST_ANCHORED requires an identity link to have existed at every prior step, so an
        // empty link here is not a normal "never linked" case — it means the link row itself is gone
        // (or the identity has otherwise dropped its binding entirely) since this ceremony was created,
        // which is the same "you're no longer the identity this ceremony was created for" problem as an
        // outright relink, just without a binding_version left to compare against.
        //
        // Deliberately a plain (unlocked) read, not KeriIdentityLinkRepository#findByUserIdForUpdate
        // (F3 fix, design §4.7): this method never writes to the identity link, only reads its
        // bindingVersion/aid to decide the CEREMONY's own transition — the ceremony row itself is
        // already row-locked above, and the write this method performs is entirely on that ceremony row,
        // never on the link. The lock exists to serialize concurrent WRITERS of the link row (relink vs.
        // the async persist*IfIdentityStillCurrent mutators); a read-only consumer of the link's current
        // value has nothing to serialize against and doesn't need it.
        Optional<KeriIdentityLinkEntity> linkOpt = identityLinkRepository.findById(userId);
        if (linkOpt.isEmpty() || linkOpt.get().getBindingVersion() != ceremony.getBindingVersion()) {
            return Either.left(KeriAttestationProblems.conflict(KeriAttestationProblems.IDENTITY_RELINKED,
                    "The identity behind ceremony %s has been relinked since it was created.".formatted(ceremonyId)));
        }
        KeriIdentityLinkEntity link = linkOpt.get();

        ceremony.setState(CeremonyState.CONSUMED);
        ceremony.setUpdatedAt(LocalDateTime.now());
        ceremonyRepository.save(ceremony);

        return Either.right(new ConsumedAttestation(ceremony.getId(), link.getAid(), ceremony.getMetadataDigest(),
                ceremony.getMetadataLabel(), ceremony.getKelSequence()));
    }

    // --- internals ---

    /**
     * Recomputes the fast-forward floor from the ceremony owner's CURRENT identity link and advances
     * the ceremony in place if it is behind (F1 fix, design §4.2). Only ever moves a ceremony sitting
     * in one of {@link #LINK_ADVANCEABLE_STATES} — a waiting step, a terminal state, or
     * {@code ATTEST_ANCHORED} is left untouched. Guarded on {@code bindingVersion} matching the link's
     * current value so a relinked ceremony (already being invalidated by {@code KeriOobiService}, or
     * about to be) is never advanced using the new identity's progress either. Never touches
     * {@code attemptGeneration} — this is not a step transition, just catching the ceremony's resting
     * state up to what the identity link already reflects.
     *
     * <p>The link read below is deliberately a plain (unlocked) {@code findById}, not
     * {@code KeriIdentityLinkRepository#findByUserIdForUpdate} (F3 fix, design §4.7): like
     * {@link #validateAndConsume}, this method only ever reads the link to derive a floor for the
     * CEREMONY row (already row-locked by every caller of this private method) — it never writes to the
     * link, so it has nothing to serialize against the link row's actual writers (relink, and the async
     * {@code persist*IfIdentityStillCurrent} mutators).
     */
    private void advanceToLinkDerivedFloor(KeriAttestationCeremonyEntity ceremony) {
        if (!LINK_ADVANCEABLE_STATES.contains(ceremony.getState())) {
            return;
        }
        Optional<KeriIdentityLinkEntity> linkOpt = identityLinkRepository.findById(ceremony.getUserId());
        if (linkOpt.isEmpty() || linkOpt.get().getBindingVersion() != ceremony.getBindingVersion()) {
            return;
        }
        CeremonyState floor = fastForwardState(linkOpt);
        if (floor.ordinal() > ceremony.getState().ordinal()) {
            ceremony.setState(floor);
            ceremony.setUpdatedAt(LocalDateTime.now());
            ceremonyRepository.save(ceremony);
        }
    }

    private static CeremonyState fastForwardState(Optional<KeriIdentityLinkEntity> linkOpt) {
        if (linkOpt.isEmpty()) {
            return CeremonyState.CREATED;
        }
        KeriIdentityLinkEntity link = linkOpt.get();
        if (link.getAid() == null) {
            return CeremonyState.CREATED;
        }
        if (link.getCredentialSaid() == null) {
            return CeremonyState.OOBI_RESOLVED;
        }
        if (link.getAuthBeginTxHash() == null) {
            return CeremonyState.CREDENTIAL_RECEIVED;
        }
        return CeremonyState.AUTH_BEGIN_CONFIRMED;
    }

    /**
     * A step is required exactly while the ceremony's state precedes the state that step's
     * completion produces — the same rule {@link #fastForwardState} uses to pick a ceremony's initial
     * state, generalized to any state so it also drives {@link #get}'s view of an in-flight ceremony.
     */
    private static RequiredSteps requiredStepsFor(CeremonyState state) {
        boolean oobi = state.ordinal() < CeremonyState.OOBI_RESOLVED.ordinal();
        boolean credential = state.ordinal() < CeremonyState.CREDENTIAL_RECEIVED.ordinal();
        boolean authBegin = state.ordinal() < CeremonyState.AUTH_BEGIN_CONFIRMED.ordinal();
        return new RequiredSteps(oobi, credential, authBegin);
    }

    private static CeremonyView toView(KeriAttestationCeremonyEntity ceremony, String authBeginTxHash) {
        return new CeremonyView(ceremony.getId(), ceremony.getState(), requiredStepsFor(ceremony.getState()),
                ceremony.getErrorTitle(), ceremony.getErrorDetail(), ceremony.getMetadataDigest(),
                ceremony.getKelSequence(), ceremony.getKelEventSaid(), authBeginTxHash);
    }

    /** Returns {@code true} if the ceremony is (now, or already was) EXPIRED. Mutates and persists
     *  the transition the first time it is observed past due — see design §4.2 "expiry is lazy". */
    private boolean lazilyExpireIfNeeded(KeriAttestationCeremonyEntity ceremony) {
        if (TERMINAL_STATES.contains(ceremony.getState())) {
            return ceremony.getState() == CeremonyState.EXPIRED;
        }
        if (!ceremony.getExpiresAt().isAfter(LocalDateTime.now())) {
            ceremony.setState(CeremonyState.EXPIRED);
            ceremony.setUpdatedAt(LocalDateTime.now());
            ceremonyRepository.save(ceremony);
            return true;
        }
        return false;
    }

    private static ProblemDetail notFoundProblem(String ceremonyId) {
        return KeriAttestationProblems.notFound(KeriAttestationProblems.CEREMONY_NOT_FOUND,
                "Ceremony %s was not found.".formatted(ceremonyId));
    }

    private static ProblemDetail forbiddenProblem(String ceremonyId) {
        return KeriAttestationProblems.forbidden(
                "Ceremony %s does not belong to the current user.".formatted(ceremonyId));
    }

    private static ProblemDetail invalidStateProblem(String ceremonyId, CeremonyState expected, CeremonyState actual) {
        return KeriAttestationProblems.conflict(KeriAttestationProblems.CEREMONY_INVALID_STATE,
                "Ceremony %s expected state %s but was %s.".formatted(ceremonyId, expected, actual));
    }

    private static ProblemDetail expiredProblem(String ceremonyId) {
        return KeriAttestationProblems.conflict(KeriAttestationProblems.CEREMONY_EXPIRED,
                "Ceremony %s has expired.".formatted(ceremonyId));
    }
}
