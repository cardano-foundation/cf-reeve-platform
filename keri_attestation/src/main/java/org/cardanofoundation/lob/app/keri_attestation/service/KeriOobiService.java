package org.cardanofoundation.lob.app.keri_attestation.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.coring.Operations;

/**
 * Resolves a user's wallet OOBI into an AID and creates/updates their {@link KeriIdentityLinkEntity}
 * (design §4.3/§4.7).
 *
 * <p>All syntactic validation ({@link #validate}) runs before any {@link SignifyClient} call — an
 * invalid OOBI URL never touches the KERI agent. Once validated, the URL is resolved against the
 * agent (alias = the caller's {@code userId}, stable per user) and the resulting AID is verified via
 * {@code contacts().get(aid)} before anything is persisted. Persistence then branches on the user's
 * existing {@link KeriIdentityLinkEntity} (design §4.7): no link creates one at
 * {@code bindingVersion=1}; the same AID just refreshes {@code oobiUrl}; a different AID requires
 * {@code relink=true} and, when granted, bumps {@code bindingVersion}, clears every field that only
 * makes sense under the old identity, and fails every one of the user's still-open ceremonies so a
 * ceremony created under the stale binding can never be consumed (mirrors
 * {@link CeremonyService#validateAndConsume}'s own binding-version check, just proactive instead of
 * reactive).
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriOobiService {

    static final int MAX_OOBI_URL_LENGTH = 2048;
    private static final long RESOLVE_TIMEOUT_MILLIS = 15_000L;
    private static final Pattern OOBI_AID_PATTERN = Pattern.compile("/oobi/([^/]+)");
    private static final Set<CeremonyState> TERMINAL_STATES =
            EnumSet.of(CeremonyState.CONSUMED, CeremonyState.FAILED, CeremonyState.EXPIRED);

    private final KeriAttestationClient client;
    private final KeriIdentityLinkRepository identityLinkRepository;
    private final KeriAttestationCeremonyRepository ceremonyRepository;
    private final ApplicationEventPublisher eventPublisher;

    public Either<ProblemDetail, String> resolveUserOobi(String userId, String oobiUrl, boolean relink) {
        Either<ProblemDetail, String> validated = validate(oobiUrl);
        if (validated.isLeft()) {
            return validated;
        }
        String aid = validated.get();

        Either<ProblemDetail, Void> resolved = resolveAndVerify(userId, oobiUrl, aid);
        if (resolved.isLeft()) {
            return Either.left(resolved.getLeft());
        }

        return persistLink(userId, aid, oobiUrl, relink);
    }

    // --- validation (design §4.3): runs before any client call, so an invalid URL never touches
    //     the KERI agent. ---

    private static Either<ProblemDetail, String> validate(String oobiUrl) {
        if (oobiUrl == null || oobiUrl.isBlank()) {
            return Either.left(invalid("OOBI URL must not be blank."));
        }
        if (oobiUrl.length() > MAX_OOBI_URL_LENGTH) {
            return Either.left(invalid(
                    "OOBI URL exceeds the maximum length of %d characters.".formatted(MAX_OOBI_URL_LENGTH)));
        }
        URI uri;
        try {
            uri = new URI(oobiUrl);
        } catch (URISyntaxException e) {
            return Either.left(invalid("OOBI URL is not a syntactically valid URL: %s".formatted(e.getMessage())));
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return Either.left(invalid("OOBI URL must use the https scheme."));
        }
        Matcher matcher = OOBI_AID_PATTERN.matcher(oobiUrl);
        if (!matcher.find() || matcher.group(1).isBlank()) {
            return Either.left(invalid("OOBI URL must contain a non-empty /oobi/{aid} path segment."));
        }
        return Either.right(matcher.group(1));
    }

    // --- resolve against the KERI agent and verify the AID landed in contacts ---

    private Either<ProblemDetail, Void> resolveAndVerify(String userId, String oobiUrl, String aid) {
        try {
            Object resolveResult = client.client().oobis().resolve(oobiUrl, userId);
            Operations.WaitOptions waitOptions = Operations.WaitOptions.builder()
                    .abortSignal(Operations.AbortSignal.builder().timeout(RESOLVE_TIMEOUT_MILLIS).build())
                    .build();
            client.client().operations().wait(Operation.fromObject(resolveResult), waitOptions);

            Optional<Object> contact = client.client().contacts().get(aid);
            if (contact.isEmpty()) {
                return Either.left(invalid(
                        "AID %s could not be verified via contacts after resolving the OOBI.".formatted(aid)));
            }
        } catch (Exception e) {
            log.warn("OOBI resolve failed for user {} ({}): {}", userId, oobiUrl, e.getMessage());
            return Either.left(invalid("Failed to resolve OOBI URL: %s".formatted(e.getMessage())));
        }
        return Either.right(null);
    }

    // --- persist the identity link (design §4.7) ---

    /**
     * Decides whether this call is a create, a no-op-refresh, or a genuine relink, and dispatches
     * accordingly (F3 fix / item 4 round-2 fix — lock-order inversion).
     *
     * <p><b>Global lock order: ceremony before link</b> (see {@code CeremonyService#completeStep}'s
     * javadoc for the full statement of the rule and why it exists). Completion paths
     * ({@code CeremonyService#completeStep} invoking {@code KeriCredentialService}'s or
     * {@code KeriAuthBeginService}'s {@code persist*IfIdentityStillCurrent} mutators) always lock the
     * ceremony row first and the identity-link row second. A relink here needs BOTH locks too — it
     * writes the link and, when it's a genuine relink, also row-locks every one of the user's open
     * ceremonies to invalidate them — so it must acquire them in the very same order, or two
     * transactions taking the same two locks in opposite orders can deadlock. The original
     * implementation locked the link first (to decide create/refresh/relink) and only then locked
     * ceremonies (to invalidate them) — exactly the inverted order.
     *
     * <p>Fixed by splitting the decision from the write:
     * <ol>
     *   <li>a plain, <b>unlocked</b> read of the link decides which kind of write this looks like,
     *       without taking the link lock yet;</li>
     *   <li>if that looks like a genuine relink (a different AID, {@code relink=true}), open ceremonies
     *       are invalidated FIRST — ceremony row locks only, the link lock is not held during this
     *       step;</li>
     *   <li>only then is the link row locked, via {@link #lockAndUpsertLink}, which re-derives the
     *       create/refresh/relink decision fresh under the lock (a concurrent write could have changed
     *       the link between the plain read and this lock) and performs the atomic write.</li>
     * </ol>
     * A create or same-AID refresh never touches a ceremony lock at all in the transaction, so those
     * paths go straight to {@link #lockAndUpsertLink} — there is no ordering hazard to avoid when only
     * one of the two lock types is ever taken.
     *
     * <p><b>Residual narrow race, accepted:</b> if the plain read (step 1) sees no relink needed but the
     * locked re-read (step 3) discovers the link actually changed AID out from under it (a genuinely
     * concurrent relink of the very same user racing this call), {@link #lockAndUpsertLink} still
     * re-decides correctly using the {@code relink} flag this call was given (rejecting with
     * {@code IDENTITY_RELINKED} if it wasn't granted, exactly as if the plain read had seen it coming) —
     * but if it WAS granted, it completes the write (bumping {@code bindingVersion} etc.) without
     * invalidating open ceremonies at that point: doing so would require taking a ceremony lock while the
     * link lock is already held, reintroducing the exact inversion this fix removes. Correctness is not
     * at risk either way: {@code CeremonyService#validateAndConsume}'s own {@code bindingVersion} check
     * still refuses to consume a ceremony created under a since-superseded binding; the only cost is that
     * such a ceremony fails lazily (at consume time, or via the TTL/stale-step sweep) rather than being
     * proactively invalidated. This race requires the same user to be relinking concurrently from two
     * places at once and is not expected in practice.
     */
    private Either<ProblemDetail, String> persistLink(String userId, String aid, String oobiUrl, boolean relink) {
        Optional<KeriIdentityLinkEntity> plainRead = identityLinkRepository.findById(userId);
        boolean looksLikeRelink = plainRead.isPresent() && !aid.equals(plainRead.get().getAid());

        if (!looksLikeRelink) {
            return lockAndUpsertLink(userId, aid, oobiUrl, relink);
        }

        if (!relink) {
            return Either.left(KeriAttestationProblems.conflict(KeriAttestationProblems.IDENTITY_RELINKED,
                    "User %s is already linked to AID %s; retry with relink=true to switch to %s."
                            .formatted(userId, plainRead.get().getAid(), aid)));
        }

        // Ceremony locks only, taken and released before the link lock is ever acquired below.
        invalidateOpenCeremonies(userId);

        return lockAndUpsertLink(userId, aid, oobiUrl, relink);
    }

    /**
     * Locks the link row and performs the actual create/refresh/relink write — the authoritative
     * decision, re-derived fresh under the lock regardless of what {@link #persistLink} inferred from
     * its earlier plain read. {@code relink} is threaded through (not just inferred from the lock) so
     * that a locked re-read revealing a genuine AID change this call was never granted {@code relink=true}
     * for is still rejected with {@code IDENTITY_RELINKED}, not silently performed. This is always the
     * LAST thing a relink does in {@link #persistLink}: never follow this call with a ceremony lock while
     * still inside the same transaction (see {@link #persistLink}'s javadoc for the lock-order rule this
     * preserves).
     */
    private Either<ProblemDetail, String> lockAndUpsertLink(String userId, String aid, String oobiUrl,
            boolean relink) {
        Optional<KeriIdentityLinkEntity> existing = identityLinkRepository.findByUserIdForUpdate(userId);
        if (existing.isEmpty()) {
            KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
            link.setUserId(userId);
            link.setBindingVersion(1);
            link.setAid(aid);
            link.setOobiUrl(oobiUrl);
            identityLinkRepository.save(link);
            return Either.right(aid);
        }

        KeriIdentityLinkEntity link = existing.get();
        if (aid.equals(link.getAid())) {
            // Same AID under the lock: either this always was a refresh, or a concurrent request already
            // relinked to this exact AID between persistLink's plain read and this lock — either way,
            // no version bump, no further ceremony invalidation needed here.
            link.setOobiUrl(oobiUrl);
            identityLinkRepository.save(link);
            return Either.right(aid);
        }

        if (!relink) {
            // Only reachable via the residual race documented on persistLink's javadoc: the plain read
            // saw no relink needed, but the link actually changed AID out from under it before this lock
            // was acquired. Must still reject -- this call was never granted relink=true.
            return Either.left(KeriAttestationProblems.conflict(KeriAttestationProblems.IDENTITY_RELINKED,
                    "User %s is already linked to AID %s; retry with relink=true to switch to %s."
                            .formatted(userId, link.getAid(), aid)));
        }

        link.setBindingVersion(link.getBindingVersion() + 1);
        link.setCredentialSaid(null);
        link.setCredentialSchemaSaid(null);
        link.setAuthBeginTxHash(null);
        link.setAuthBeginBlock(null);
        link.setAuthBeginAt(null);
        link.setAid(aid);
        link.setOobiUrl(oobiUrl);
        identityLinkRepository.save(link);
        // R2 fix (Codex re-verification): published from inside this same transaction, but only
        // DELIVERED to RelinkInvalidationSweepHandler after it commits (AFTER_COMMIT listener) - never
        // executed synchronously here, so this never risks taking a ceremony lock while the link lock
        // acquired above is still held. Closes the phantom-insert window between invalidateOpenCeremonies
        // (run before this method ever locked the link row) and this write actually landing - see
        // RelinkInvalidationSweepHandler's javadoc for the full race this closes.
        eventPublisher.publishEvent(new RelinkCompletedEvent(userId, link.getBindingVersion()));
        return Either.right(aid);
    }

    /** Fails every one of the user's non-terminal ceremonies on relink (design §4.7) — each was
     *  created under the old {@code bindingVersion} and can never be legitimately consumed once the
     *  identity behind it has changed. Direct mutation + save rather than
     *  {@link CeremonyService}'s step-CAS methods: those exist for async workers racing a retry, not
     *  for this bulk invalidation. {@code findByUserIdAndStateNotIn} is an unlocked discovery read
     *  though (same as {@code CeremonyCleanupJob}'s sweep), so each candidate is re-fetched under
     *  {@link KeriAttestationCeremonyRepository#findByIdForUpdate} and re-checked before being
     *  mutated — otherwise a concurrent legitimate transition (e.g. {@link CeremonyService
     *  #validateAndConsume} finishing the same ceremony between the discovery read and this write)
     *  could be silently clobbered back to FAILED.
     *
     *  <p><b>Global lock order: ceremony before link</b> (item 4 round-2 fix — see
     *  {@code CeremonyService#completeStep}'s javadoc for the full rule). {@link #persistLink} calls
     *  this method BEFORE ever locking the identity-link row, precisely so the ceremony row locks taken
     *  here are never held at the same time as the link lock — callers must preserve that ordering; do
     *  not call this method (or otherwise lock a ceremony row) while the link row is already locked. */
    private void invalidateOpenCeremonies(String userId) {
        List<KeriAttestationCeremonyEntity> candidates = ceremonyRepository.findByUserIdAndStateNotIn(userId, TERMINAL_STATES);
        for (KeriAttestationCeremonyEntity candidate : candidates) {
            ceremonyRepository.findByIdForUpdate(candidate.getId()).ifPresent(ceremony -> {
                if (TERMINAL_STATES.contains(ceremony.getState())) {
                    return;
                }
                ceremony.setState(CeremonyState.FAILED);
                ceremony.setErrorTitle(KeriAttestationProblems.IDENTITY_RELINKED);
                ceremony.setErrorDetail("Identity for user %s was relinked to a different AID.".formatted(userId));
                ceremonyRepository.save(ceremony);
            });
        }
    }

    private static ProblemDetail invalid(String detail) {
        return KeriAttestationProblems.unprocessable(KeriAttestationProblems.OOBI_INVALID, detail);
    }
}
