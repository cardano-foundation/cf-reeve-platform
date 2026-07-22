package org.cardanofoundation.lob.app.keri_attestation.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.CredentialChainValidator.ValidatedCredential;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriNotificationCorrelator.CorrelatedNotification;
import org.cardanofoundation.signify.app.Exchanging.ExchangeMessageResult;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.coring.Operations;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAdmitArgs;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAgreeArgs;
import org.cardanofoundation.signify.core.States.HabState;

/**
 * Drives IPEX credential presentation (design §4.3): the platform's agent AID requests a credential
 * from the user's linked wallet AID (apply), the wallet offers it (offer), the agent agrees (agree),
 * the wallet grants it (grant), the agent admits it (admit), then fetches and validates the full CESR
 * chain before persisting it to the identity link.
 *
 * <p>Split in two per the design's async step model: {@link #startPresentation} is the synchronous
 * part of a step POST (build + send the apply, persist where to correlate the reply, return quickly);
 * {@link #awaitPresentation} is the async continuation (offer → agree → grant → admit → fetch → validate
 * → persist → complete/fail the ceremony step) that a background worker runs after the synchronous part
 * returns. {@link #startCredentialRequest} (Task 10) is the orchestrating entry point the controller
 * actually calls: {@link #ensureSchemasResolved} + {@code beginStep} + a retry pre-check +
 * {@link #startPresentation} + {@link CeremonyAsyncRunner} dispatch, mirroring
 * {@link KeriAttestService#startAttest}'s shape exactly.
 *
 * <p><b>Live-testing fix:</b> {@link #ensureSchemasResolved} resolves every configured schema SAID as
 * an OOBI on OUR OWN agent before the first apply that references it is ever sent — KERIA silently
 * drops an IPEX exchange referencing a schema SAID the receiving agent has never itself resolved, so
 * without this a real wallet's "present" action does nothing observable (no error, no notification).
 * See that method's javadoc for the full rationale, mirrored from cip113's reference
 * {@code resolveSchemas} flow ({@code docs/keri/advanced/PublishExistingCredential.java}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriCredentialService {

    private static final List<String> OFFER_ROUTES = List.of("/exn/ipex/offer");
    private static final List<String> GRANT_ROUTES = List.of("/exn/ipex/grant");

    /** cip113's exact {@code KERI_DATETIME} pattern (design §4.4 rev 3, alignment item 6): passed
     *  explicitly to every exn this class builds rather than relying on the pinned signify jar's own
     *  null-datetime fallback ({@code Exchanging.exchange}), which derives its timestamp from
     *  {@code java.util.Date} and can render withOUT a fractional-seconds separator at all
     *  (e.g. {@code "...T00:00:00000+00:00"}, missing the {@code "."}) on the rare timestamp that lands
     *  on an exact whole second — a malformed {@code dt} a strict wallet-side schema check could reject
     *  outright. This formatter always renders exactly six fractional digits. */
    private static final DateTimeFormatter KERI_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'");

    /** F8 fix: {@code step_phase} values marking which half of the two-phase CREDENTIAL_REQUESTED wait
     *  (apply/offer, then agree/grant) an attempt last reached — see
     *  {@link KeriAttestationCeremonyEntity#getStepPhase()}. */
    static final String PHASE_APPLY_SENT = "APPLY_SENT";
    static final String PHASE_AGREE_SENT = "AGREE_SENT";

    /** Short wait for the retry pre-check (design §4.2 "before re-sending... checks for a
     *  late-arriving matching notification") — mirrors {@link KeriAttestService}'s own retry-precheck
     *  timeout, applied here to the credential-presentation step instead of the ATTEST anchor. */
    private static final Duration RETRY_PRECHECK_TIMEOUT = Duration.ofSeconds(2);

    /** Bounded wait for a single schema-OOBI resolve ({@link #ensureSchemasResolved}) — mirrors
     *  {@code KeriOobiService#RESOLVE_TIMEOUT_MILLIS}'s own bound on the same underlying
     *  {@code operations().wait} call, applied here to a schema OOBI instead of a wallet's identity OOBI. */
    private static final long SCHEMA_RESOLVE_TIMEOUT_MILLIS = 15_000L;

    private final KeriAttestationClient client;
    private final KeriAgentService agentService;
    private final KeriNotificationCorrelator correlator;
    private final CredentialChainValidator validator;
    private final CeremonyService ceremonyService;
    private final KeriAttestationCeremonyRepository ceremonyRepository;
    private final KeriIdentityLinkRepository identityLinkRepository;
    private final KeriAttestationProperties properties;
    private final CeremonyAsyncRunner asyncRunner;

    /** In-memory cache of schema SAIDs already resolved as an OOBI on our agent this process
     *  ({@link #ensureSchemasResolved}) — a schema, once resolved, stays resolved for the life of the
     *  agent, so this avoids re-resolving (and re-waiting on) the same SAID on every presentation. */
    private final Set<String> resolvedSchemaSaids = ConcurrentHashMap.newKeySet();

    // --- orchestration (Task 10): begin the step, avoid a redundant apply if a reply already arrived,
    //     dispatch the async continuation. The controller calls only this method. ---

    /**
     * Orchestrates the CREDENTIAL_REQUEST step end-to-end for a controller call (design §4.2), mirroring
     * {@link KeriAttestService#startAttest}: {@link CeremonyService#beginStep} from {@code OOBI_RESOLVED}
     * to {@code CREDENTIAL_REQUESTED} (or, on retry, re-enters {@code CREDENTIAL_REQUESTED} with a
     * bumped {@code attemptGeneration}), then a short retry pre-check for a reply that already arrived on
     * the previous attempt's apply before resending one.
     */
    public Either<ProblemDetail, Void> startCredentialRequest(String ceremonyId, String userId, boolean retry) {
        // Live-testing fix: resolved BEFORE beginStep, i.e. before any ceremony state is touched at
        // all — a resolution failure here must surface as a plain problem, not a failed/rolled-back
        // ceremony step. See ensureSchemasResolved's javadoc for why this has to happen at all.
        Either<ProblemDetail, Void> schemasResolved =
                ensureSchemasResolved(properties.credentialPolicy().schemaSaids());
        if (schemasResolved.isLeft()) {
            return Either.left(schemasResolved.getLeft());
        }

        Either<ProblemDetail, KeriAttestationCeremonyEntity> begun = ceremonyService.beginStep(ceremonyId, userId,
                CeremonyState.OOBI_RESOLVED, CeremonyState.CREDENTIAL_REQUESTED, retry);
        if (begun.isLeft()) {
            return Either.left(begun.getLeft());
        }
        KeriAttestationCeremonyEntity ceremony = begun.get();
        int generation = ceremony.getAttemptGeneration();

        Optional<KeriIdentityLinkEntity> linkOpt = identityLinkRepository.findById(userId);
        if (linkOpt.isEmpty() || linkOpt.get().getAid() == null) {
            return failCredentialRequest(ceremonyId, generation, KeriAttestationProblems.IDENTITY_NOT_LINKED,
                    "User %s has no linked identity to request a credential presentation from.".formatted(userId));
        }

        // F8 fix: a retry resuming at AGREE_SENT already has BOTH the apply and agree sent by a
        // previous attempt (requestExnSaid now holds the agree's SAID, not the apply's) — never
        // re-send either, and never re-run the offer precheck below (there is no offer left to claim;
        // that phase is done). awaitPresentation's own phase-aware, full-timeout wait (await grant,
        // admit, fetch, validate, complete) handles everything from here; a separate short precheck here
        // would add nothing that wait doesn't already do, since there is nothing left to avoid resending.
        if (retry && PHASE_AGREE_SENT.equals(ceremony.getStepPhase())) {
            return dispatchAwaitPresentation(ceremonyId, generation);
        }

        // Retry pre-check (design §4.2, mirrors KeriAttestService#startAttest): before sending a fresh
        // IPEX apply, look for a late-arriving offer. cip113 parity (design rev, user-directed
        // 2026-07-22): route-only, like every other offer/grant/ref claim in this module now — see
        // KeriNotificationCorrelator#awaitByRoute's javadoc. Found: skip straight to dispatching the
        // async continuation — its own (non-destructive) correlator.awaitByRoute call will find the same
        // offer again, so nothing needs re-sending. Not found (or this is the first attempt,
        // requestExnSaid still null): fall through and build + send a fresh apply below. Only reached
        // for phase APPLY_SENT or null (no phase recorded yet).
        if (retry && ceremony.getRequestExnSaid() != null) {
            Optional<CorrelatedNotification> lateOffer = correlator.awaitByRoute(OFFER_ROUTES,
                    RETRY_PRECHECK_TIMEOUT);
            if (lateOffer.isPresent()) {
                return dispatchAwaitPresentation(ceremonyId, generation);
            }
        }

        Either<ProblemDetail, Void> sent = startPresentation(ceremony);
        if (sent.isLeft()) {
            ProblemDetail problem = sent.getLeft();
            failCredentialStep(ceremonyId, generation, problem.getTitle(), problem.getDetail());
            return Either.left(problem);
        }

        return dispatchAwaitPresentation(ceremonyId, generation);
    }

    /**
     * Live-testing fix: resolves every one of {@code schemaSaids} as an OOBI on OUR OWN agent — not
     * just recognized by the wallet — before an IPEX apply referencing any of them is ever sent.
     *
     * <p>Discovered during real-wallet testing: presenting a credential from a real Veridian wallet did
     * nothing observable when clicked — no error surfaced anywhere, the apply reached the wallet fine,
     * but no notification for its reply ever arrived back on our side. Root cause: our KERIA agent had
     * never resolved the credential schema's OOBI itself. KERIA silently drops an IPEX exchange
     * referencing a schema SAID the receiving agent doesn't already know, so the wallet's offer — sent
     * in response to a perfectly valid apply — is dropped before it ever becomes a notification here.
     * cip113's reference flow avoids this by resolving every schema OOBI up front (see
     * {@code docs/keri/advanced/PublishExistingCredential.java}'s {@code resolveSchemas}: for each
     * schema, {@code client.oobis().resolve(schemaBaseUrl + "/" + said, null)} followed by
     * {@code client.operations().wait(...)}); this mirrors that, but lazily — once per SAID per
     * process, cached in {@link #resolvedSchemaSaids} — rather than eagerly at startup.
     *
     * <p>Called at the very top of {@link #startCredentialRequest}, before {@code beginStep} touches
     * any ceremony state at all: a resolution failure must surface as a plain problem, never a
     * failed/rolled-back ceremony step.
     */
    private Either<ProblemDetail, Void> ensureSchemasResolved(List<String> schemaSaids) {
        if (schemaSaids == null || schemaSaids.isEmpty()) {
            return Either.right(null);
        }
        String baseUrl = withoutTrailingSlash(properties.credentialPolicy().schemaBaseUrl());
        for (String said : schemaSaids) {
            if (resolvedSchemaSaids.contains(said)) {
                continue;
            }
            String schemaUrl = baseUrl + "/" + said;
            try {
                Object resolveResult = client.client().oobis().resolve(schemaUrl, null);
                Operations.WaitOptions waitOptions = Operations.WaitOptions.builder()
                        .abortSignal(Operations.AbortSignal.builder().timeout(SCHEMA_RESOLVE_TIMEOUT_MILLIS).build())
                        .build();
                client.client().operations().wait(Operation.fromObject(resolveResult), waitOptions);
                resolvedSchemaSaids.add(said);
            } catch (Exception e) {
                interruptIfNeeded(e);
                log.warn("Failed to resolve schema OOBI {} on the agent: {}", schemaUrl, e.getMessage());
                return Either.left(KeriAttestationProblems.serviceUnavailable(
                        KeriAttestationProblems.KERI_AGENT_UNAVAILABLE,
                        "Failed to resolve schema OOBI %s on the agent: %s".formatted(schemaUrl, e.getMessage())));
            }
        }
        return Either.right(null);
    }

    private Either<ProblemDetail, Void> dispatchAwaitPresentation(String ceremonyId, int generation) {
        try {
            asyncRunner.awaitPresentation(ceremonyId, generation);
        } catch (Exception e) {
            // The executor rejected the dispatch (pool/queue saturated) — the apply (if this attempt
            // sent one) is already on its way to the wallet, but with no worker left to await its
            // reply, the ceremony must not be left non-terminal with an unhandled exception as the only
            // signal. A retry's pre-check (above) will pick up a late-arriving offer instead of
            // resending. Mirrors KeriAttestService#startAttest's identical dispatch-failure handling.
            log.warn("Failed to dispatch credential presentation wait for ceremony {}: {}", ceremonyId,
                    e.getMessage());
            return failCredentialRequest(ceremonyId, generation, KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED,
                    "Failed to dispatch the presentation wait: " + e.getMessage());
        }
        return Either.right(null);
    }

    private Either<ProblemDetail, Void> failCredentialRequest(String ceremonyId, int generation, String title,
            String detail) {
        failCredentialStep(ceremonyId, generation, title, detail);
        return Either.left(KeriAttestationProblems.unprocessable(title, detail));
    }

    /** F8 fix: fails the CREDENTIAL_REQUESTED step, first best-effort clearing {@code stepPhase} so a
     *  terminal (FAILED) row never carries a stale phase marker. The clear is a separate guarded update
     *  from the {@code failStep} CAS that follows it — both independently no-op if the ceremony has
     *  since moved on, which is harmless: clearing a phase that is about to become moot changes nothing
     *  observable. */
    private void failCredentialStep(String ceremonyId, int expectedGeneration, String title, String detail) {
        ceremonyService.updateWaitingStepData(ceremonyId, expectedGeneration, CeremonyState.CREDENTIAL_REQUESTED,
                c -> c.setStepPhase(null));
        ceremonyService.failStep(ceremonyId, expectedGeneration, CeremonyState.CREDENTIAL_REQUESTED, title, detail);
    }

    // --- synchronous: build + send the apply, persist requestExnSaid before the send completes ---

    public Either<ProblemDetail, Void> startPresentation(KeriAttestationCeremonyEntity ceremony) {
        Optional<KeriIdentityLinkEntity> linkOpt = identityLinkRepository.findById(ceremony.getUserId());
        if (linkOpt.isEmpty() || linkOpt.get().getAid() == null) {
            return Either.left(identityNotLinked(ceremony.getUserId()));
        }
        String linkedAid = linkOpt.get().getAid();
        String agentName = agentService.agentName();

        try {
            List<String> schemaSaids = properties.credentialPolicy().schemaSaids();
            if (schemaSaids == null || schemaSaids.isEmpty()) {
                return Either.left(requestFailed(
                        "No schema SAIDs configured under lob.keri-attestation.credential-policy.schema-saids."));
            }
            String schemaSaid = schemaSaids.get(0);

            Optional<HabState> senderOpt = client.client().identifiers().get(agentName);
            if (senderOpt.isEmpty()) {
                return Either.left(requestFailed(
                        "No local HabState found for agent identifier %s.".formatted(agentName)));
            }

            // cip113 wallet contract (design §4.3/§4.4 rev 3, KeriService#presentCredential): build
            // /ipex/apply directly via createExchangeMessage, with oobiUrl at the TOP level of the
            // payload (exn.a.oobiUrl). IpexApplyArgs#attributes (the old approach) lands under exn.a.a
            // instead: Ipex#apply puts args.getAttributes() at data["a"], and createExchangeMessage's
            // payload becomes exn.a as a whole, so oobiUrl ends up doubly nested at exn.a.a.oobiUrl,
            // which the wallet never finds.
            //
            // Live-testing fix: oobiUrl must be the CREDENTIAL SCHEMA SERVER's base URL (configured as
            // lob.keri-attestation.credential-policy.schema-base-url), NOT our agent's own OOBI — this
            // is where a Veridian-style wallet actually resolves the schema behind the apply's SAID
            // from, with a trailing slash (proven working against a real wallet during live testing).
            Map<String, Object> applyData = new LinkedHashMap<>();
            applyData.put("m", "");
            applyData.put("s", schemaSaid);
            applyData.put("a", new LinkedHashMap<>());
            applyData.put("oobiUrl", withTrailingSlash(properties.credentialPolicy().schemaBaseUrl()));
            ExchangeMessageResult applyResult = client.client().exchanges().createExchangeMessage(
                    senderOpt.get(), "/ipex/apply", applyData, new LinkedHashMap<>(), linkedAid,
                    nowKeriTimestamp(), null);
            String exnSaid = (String) applyResult.exn().getKed().get("d");

            // Persist BEFORE the send completes (design §4.6 pattern applied here too): the SAID is
            // deterministic from the built (unsent) exn, so if the network call below fails partway
            // through, the ceremony still records what was — or was about to be — sent, and a retry can
            // check for a late-arriving correlated reply before re-sending. Routed through the guarded
            // update (F2 fix) rather than a direct save of this detached entity: a concurrent retry/sweep
            // transition landing between beginStep's row lock releasing and this write must never be
            // silently overwritten. Also records the APPLY_SENT phase (F8 fix) so a later retry knows
            // this attempt got at least this far.
            boolean persisted = ceremonyService.updateWaitingStepData(ceremony.getId(), ceremony.getAttemptGeneration(),
                    CeremonyState.CREDENTIAL_REQUESTED, c -> {
                        c.setRequestExnSaid(exnSaid);
                        c.setStepPhase(PHASE_APPLY_SENT);
                    });
            if (!persisted) {
                return Either.left(staleCeremonyProblem(ceremony.getId()));
            }

            // cip113 parity (KeriService#presentCredential): every IPEX submit is followed by
            // operations().wait, not just fire-and-forget — submitApply returns a raw operation
            // descriptor that Operation.fromObject wraps for the typed wait() overload (same idiom
            // ensureSchemasResolved already uses for oobis().resolve's own raw result).
            Object applyOp = client.client().ipex().submitApply(agentName, applyResult.exn(), applyResult.sigs(),
                    List.of(linkedAid));
            client.client().operations().wait(Operation.fromObject(applyOp));
            return Either.right(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Either.left(requestFailed("Interrupted while sending IPEX apply: " + e.getMessage()));
        } catch (Exception e) {
            log.warn("Failed to send IPEX apply for ceremony {}: {}", ceremony.getId(), e.getMessage());
            return Either.left(requestFailed("Failed to send IPEX apply: " + e.getMessage()));
        }
    }

    // --- asynchronous continuation: offer -> agree -> grant -> admit -> fetch -> validate -> persist ---

    /**
     * Runs unsupervised on a background worker (Task 9) with no caller left to report a thrown
     * exception to — an escaped exception here would leave the ceremony stuck at
     * {@code CREDENTIAL_REQUESTED} forever instead of landing in a terminal, retryable state. Every
     * external-boundary call (signify-java client calls, the correlator, the validator) is therefore
     * deliberately wrapped in a catch broad enough to guarantee this method always resolves the
     * ceremony via {@code completeStep} or {@code failStep} before returning, never by propagating.
     *
     * <p><b>Phase-aware (F8 fix):</b> when this attempt resumes at {@code stepPhase == AGREE_SENT}
     * (persisted by a previous attempt that got at least that far), the offer wait and agree send are
     * both skipped entirely — {@code requestExnSaid} already holds the agree's SAID from that persist,
     * so this jumps straight to awaiting the grant. Otherwise (phase {@code APPLY_SENT} or {@code null})
     * the normal offer-wait/agree-send happens, and its own persisted phase transition to
     * {@code AGREE_SENT} is what a later retry would resume from.
     */
    public void awaitPresentation(String ceremonyId, int expectedGeneration) {
        Optional<KeriAttestationCeremonyEntity> ceremonyOpt = ceremonyRepository.findById(ceremonyId);
        if (ceremonyOpt.isEmpty()) {
            return;
        }
        KeriAttestationCeremonyEntity ceremony = ceremonyOpt.get();

        Optional<KeriIdentityLinkEntity> linkOpt = identityLinkRepository.findById(ceremony.getUserId());
        if (linkOpt.isEmpty() || linkOpt.get().getAid() == null) {
            failCredentialStep(ceremonyId, expectedGeneration, KeriAttestationProblems.IDENTITY_NOT_LINKED,
                    "User %s has no linked identity to await a presentation from.".formatted(ceremony.getUserId()));
            return;
        }
        String linkedAid = linkOpt.get().getAid();
        String agentName = agentService.agentName();

        String agreeSaid;
        String agreeAtc;

        if (PHASE_AGREE_SENT.equals(ceremony.getStepPhase())) {
            // F8 fix: resuming after a retry that already sent the apply AND the agree — requestExnSaid
            // was overwritten to the agree's SAID when that phase was persisted, and agreeAtc alongside
            // it (cip113 parity, see below).
            agreeSaid = ceremony.getRequestExnSaid();
            agreeAtc = ceremony.getAgreeAtc();
        } else {
            // cip113 parity (KeriService#presentCredential, design rev, user-directed 2026-07-22):
            // route-only claim — see KeriNotificationCorrelator#awaitByRoute's javadoc for why this
            // module's own sender/thread-back correlation strictness yields here.
            Optional<CorrelatedNotification> offer = correlator.awaitByRoute(OFFER_ROUTES,
                    properties.remotesignTimeout());
            if (offer.isEmpty()) {
                failTimeout(ceremonyId, expectedGeneration, "Timed out waiting for /exn/ipex/offer.");
                return;
            }
            CorrelatedNotification offerNotification = offer.get();

            ExchangeMessageResult agreeResult;
            try {
                agreeResult = client.client().ipex().agree(IpexAgreeArgs.builder()
                        .senderName(agentName).recipient(linkedAid).message("")
                        .offerSaid(offerNotification.exnSaid()).datetime(nowKeriTimestamp()).build());
            } catch (Exception e) {
                interruptIfNeeded(e);
                failRequest(ceremonyId, expectedGeneration, "Failed to build IPEX agree: " + e.getMessage());
                return;
            }
            agreeSaid = (String) agreeResult.exn().getKed().get("d");
            // cip113 parity: submitAdmit (below) must be given THIS agree's atc, not the admit's own —
            // see agreeAtc's persistence just below for why it survives a worker restart.
            agreeAtc = agreeResult.atc();

            // F8 residual fix: persist phase=AGREE_SENT + requestExnSaid=agreeSaid (overwriting the
            // apply's SAID — design: requestExnSaid always names whichever exn this ceremony is
            // currently waiting on a correlated reply for) + agreeAtc BEFORE calling submitAgree, not
            // after. The agree's SAID/atc are deterministic from the built (not-yet-sent) exn, matching
            // startPresentation's/KeriAttestService#startAttest's persist-before-send idiom exactly. This
            // guarded update also refreshes updatedAt — the F7 heartbeat that keeps a legitimately
            // in-progress two-phase wait from looking stale to the cleanup sweep's budget for
            // CREDENTIAL_REQUESTED.
            //
            // A crash between this persist committing and submitAgree actually reaching the wallet is
            // safe: the agree was never sent, so no grant will ever arrive for it. A retry resuming at
            // AGREE_SENT skips straight to awaiting a grant correlated to the persisted agreeSaid (never
            // re-sending — see the class javadoc); finding none, it times out after remotesignTimeout
            // and fails the step via KERI_WALLET_TIMEOUT, exactly the outcome a genuine dropped-agree
            // would produce. No special-casing needed for the crash window.
            boolean phasePersisted = ceremonyService.updateWaitingStepData(ceremonyId, expectedGeneration,
                    CeremonyState.CREDENTIAL_REQUESTED, c -> {
                        c.setStepPhase(PHASE_AGREE_SENT);
                        c.setRequestExnSaid(agreeSaid);
                        c.setAgreeAtc(agreeAtc);
                    });
            if (!phasePersisted) {
                // Stale CAS (a retry superseded this attempt) — never send; the winning attempt's own
                // wait handles everything from here, and the offer notification is left unread/undeleted
                // for whichever attempt still needs it.
                return;
            }

            // cip113 parity: the offer notification is claimed (marked + deleted) as soon as it's no
            // longer needed — right after the phase transition proves this attempt is not superseded,
            // and before the agree is even sent, mirroring KeriService#presentCredential's own
            // fetch-then-immediately-markAndDelete ordering. Unlike the grant notification below (whose
            // delete stays gated on this module's OWN post-admit CredentialChainValidator step — additive
            // hardening cip113 has no equivalent of, kept per the design's instruction), nothing further
            // ever re-examines the offer, so there's no reason to hold onto it any longer than cip113
            // itself does.
            correlator.markAndDelete(offerNotification.notificationId());

            try {
                Object agreeOp = client.client().ipex().submitAgree(agentName, agreeResult.exn(),
                        agreeResult.sigs(), List.of(linkedAid));
                client.client().operations().wait(Operation.fromObject(agreeOp));
            } catch (Exception e) {
                interruptIfNeeded(e);
                failRequest(ceremonyId, expectedGeneration, "Failed to send IPEX agree: " + e.getMessage());
                return;
            }
        }

        // cip113 parity: route-only claim — see the offer wait above / awaitByRoute's javadoc.
        Optional<CorrelatedNotification> grant = correlator.awaitByRoute(GRANT_ROUTES, properties.remotesignTimeout());
        if (grant.isEmpty()) {
            failTimeout(ceremonyId, expectedGeneration, "Timed out waiting for /exn/ipex/grant.");
            return;
        }

        String credentialSaid = extractCredentialSaid(grant.get().exn());
        if (credentialSaid == null) {
            failRequest(ceremonyId, expectedGeneration,
                    "IPEX grant exchange did not embed an ACDC (e.acdc.d missing).");
            return;
        }

        try {
            ExchangeMessageResult admitResult = client.client().ipex().admit(IpexAdmitArgs.builder()
                    .senderName(agentName).recipient(linkedAid).message("")
                    .grantSaid(grant.get().exnSaid()).datetime(nowKeriTimestamp()).build());
            // cip113 parity (KeriService#presentCredential): submitAdmit is given the AGREE exchange's
            // own atc, NOT the admit's own — a proven cip113 wallet-contract quirk this module now
            // matches exactly (see agreeAtc above).
            Object admitOp = client.client().ipex().submitAdmit(agentName, admitResult.exn(), admitResult.sigs(),
                    agreeAtc, List.of(linkedAid));
            client.client().operations().wait(Operation.fromObject(admitOp));
        } catch (Exception e) {
            interruptIfNeeded(e);
            failRequest(ceremonyId, expectedGeneration, "Failed to admit IPEX grant: " + e.getMessage());
            return;
        }

        String fullCesr;
        try {
            Optional<String> cesrOpt = client.client().credentials().get(credentialSaid);
            if (cesrOpt.isEmpty()) {
                failRequest(ceremonyId, expectedGeneration,
                        "Credential %s was not found in the store after admit.".formatted(credentialSaid));
                return;
            }
            fullCesr = cesrOpt.get();
        } catch (Exception e) {
            interruptIfNeeded(e);
            failRequest(ceremonyId, expectedGeneration,
                    "Failed to fetch credential %s: %s".formatted(credentialSaid, e.getMessage()));
            return;
        }

        // This is the only external-boundary call in this method not backed by a checked-exception
        // contract, so it's easy to forget it can still throw (e.g. a malformed/hostile chain tripping
        // an assumption CredentialChainValidator didn't explicitly guard) — wrapped the same as every
        // other step so a defect here fails the ceremony instead of the worker.
        Either<ProblemDetail, ValidatedCredential> validated;
        try {
            validated = validator.validate(fullCesr, linkedAid, properties.credentialPolicy().schemaSaids(),
                    properties.credentialPolicy().trustedRootAids());
        } catch (Exception e) {
            interruptIfNeeded(e);
            failCredentialStep(ceremonyId, expectedGeneration, KeriAttestationProblems.CREDENTIAL_REJECTED,
                    "Chain validation error: " + e.getMessage());
            return;
        }
        if (validated.isLeft()) {
            failCredentialStep(ceremonyId, expectedGeneration, KeriAttestationProblems.CREDENTIAL_REJECTED,
                    validated.getLeft().getDetail());
            return;
        }
        ValidatedCredential vc = validated.get();

        // Defense-in-depth: the validator finds its leaf by issuee match, independently of the SAID we
        // fetched the stream for — they must agree. A mismatch would mean the presented stream's
        // issuee-matching leaf isn't the credential the grant/admit round trip was actually about,
        // which should be structurally impossible but is cheap to assert outright rather than trust.
        if (!credentialSaid.equals(vc.credentialSaid())) {
            failCredentialStep(ceremonyId, expectedGeneration, KeriAttestationProblems.CREDENTIAL_REJECTED,
                    "Validated leaf credential %s does not match the fetched credential %s."
                            .formatted(vc.credentialSaid(), credentialSaid));
            return;
        }

        // Fold the link write into completeStep's mutator (mirrors
        // KeriAuthBeginService#persistAuthBeginIfIdentityStillCurrent exactly): the credential write and
        // the ceremony's CREDENTIAL_REQUESTED -> CREDENTIAL_RECEIVED transition must commit atomically,
        // in CeremonyService's one transaction. The old code saved the link in its own separate
        // transaction before ever calling completeStep, so a stale CAS below (a retry superseded this
        // attempt) still left the link durably written even though the ceremony transition it belongs
        // with never happened. Also clears stepPhase (F8 fix) — the step is done, so no phase marker
        // should linger on the row.
        String userId = ceremony.getUserId();
        int bindingVersion = ceremony.getBindingVersion();
        String finalCredentialSaid = vc.credentialSaid();
        String finalSchemaSaid = vc.schemaSaid();

        boolean completed = ceremonyService.completeStep(ceremonyId, expectedGeneration,
                CeremonyState.CREDENTIAL_REQUESTED, CeremonyState.CREDENTIAL_RECEIVED,
                c -> {
                    persistCredentialIfIdentityStillCurrent(userId, bindingVersion, finalCredentialSaid,
                            finalSchemaSaid);
                    c.setStepPhase(null);
                });
        if (!completed) {
            // Stale CAS (a retry superseded this attempt) — the link must not be written either (the
            // mutator above never runs), and the notifications must be left alone: the winning attempt's
            // own correlator wait is (or will be) matching against the same requests and needs to find
            // them still unread/undeleted.
            return;
        }

        // Only after both the link and the ceremony transition are durably committed: an earlier
        // mark-and-delete would let a crash between the two silently lose the wallet's reply, exactly the
        // failure mode KeriNotificationCorrelator#markAndDelete's contract exists to prevent. The offer
        // notification (if any was claimed by this attempt) was already marked+deleted above, right
        // after it stopped being needed — see that call site's comment for why its ordering differs from
        // the grant's own here.
        correlator.markAndDelete(grant.get().notificationId());
    }

    // --- internals ---

    /**
     * Persists the validated credential to the identity link. <b>Only ever called from inside a
     * {@link CeremonyService#completeStep} mutator</b> — same rationale as
     * {@link KeriAuthBeginService#persistAuthBeginIfIdentityStillCurrent}'s javadoc: {@code
     * completeStep} only invokes its mutator after the ceremony row's own {@code (state,
     * attemptGeneration)} CAS has already confirmed this is the current, non-superseded attempt, so a
     * stale attempt's mutator never runs, and both the link write and the ceremony transition commit
     * together in {@code CeremonyService}'s one transaction. The re-fetch + bindingVersion re-check here
     * additionally guards the one race {@code completeStep}'s own CAS does not cover: a relink landing
     * mid-flight (a separate {@code KeriOobiService} write, out-of-band of the ceremony's own CAS
     * fields) must never let a stale write re-attach a credential to what is now a different identity —
     * the ceremony step itself still completes (the CAS on {@code (state, attemptGeneration)} has no
     * way to know about the relink); {@link CeremonyService#validateAndConsume}'s own bindingVersion
     * check is the final safety net at consumption time.
     *
     * <p>The re-fetch is row-locked (F3 fix) via
     * {@link org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository#findByUserIdForUpdate}
     * rather than a plain {@code findById}: this write and {@code KeriOobiService}'s relink write race
     * the same row, and without the lock the two could interleave into a row that is a mix of the old
     * and new identity (e.g. a relinked {@code aid} with a credential that belongs to the old AID).
     */
    private void persistCredentialIfIdentityStillCurrent(String userId, int expectedBindingVersion,
            String credentialSaid, String credentialSchemaSaid) {
        identityLinkRepository.findByUserIdForUpdate(userId).ifPresent(freshLink -> {
            if (freshLink.getBindingVersion() != expectedBindingVersion) {
                log.warn("Skipping credential link write for user {}: identity was relinked (expected binding "
                        + "version {}, now {}).", userId, expectedBindingVersion, freshLink.getBindingVersion());
                return;
            }
            freshLink.setCredentialSaid(credentialSaid);
            freshLink.setCredentialSchemaSaid(credentialSchemaSaid);
            identityLinkRepository.save(freshLink);
        });
    }

    private static String extractCredentialSaid(Map<String, Object> grantExn) {
        Object e = grantExn.get("e");
        if (!(e instanceof Map<?, ?> em)) {
            return null;
        }
        Object acdc = em.get("acdc");
        if (!(acdc instanceof Map<?, ?> am)) {
            return null;
        }
        Object said = am.get("d");
        return said instanceof String s ? s : null;
    }

    /** Item 5 (round 2) fix: routed through {@link #failCredentialStep} rather than calling
     *  {@code ceremonyService.failStep} directly — every failure exit from this class must clear
     *  {@code stepPhase} the same way, not just the ones that happened to be wired through the helper
     *  already. */
    private void failTimeout(String ceremonyId, int expectedGeneration, String detail) {
        failCredentialStep(ceremonyId, expectedGeneration, KeriAttestationProblems.KERI_WALLET_TIMEOUT, detail);
    }

    /** Item 5 (round 2) fix: see {@link #failTimeout}'s javadoc — same reasoning. */
    private void failRequest(String ceremonyId, int expectedGeneration, String detail) {
        log.warn("Credential presentation failed for ceremony {}: {}", ceremonyId, detail);
        failCredentialStep(ceremonyId, expectedGeneration, KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED, detail);
    }

    private static void interruptIfNeeded(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /** See {@link #KERI_DATETIME}'s javadoc. */
    private static String nowKeriTimestamp() {
        return KERI_DATETIME.format(LocalDateTime.now(ZoneOffset.UTC));
    }

    /** Normalizes {@code schemaBaseUrl} to end with exactly one trailing slash — the apply payload's
     *  {@code oobiUrl} (live-testing fix) needs one; accepts either input form (configured with or
     *  without one). */
    private static String withTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    /** Normalizes {@code schemaBaseUrl} to end with NO trailing slash — {@link #ensureSchemasResolved}
     *  builds each schema OOBI URL as {@code baseUrl + "/" + said}, which would double up the slash if
     *  {@code schemaBaseUrl} was itself configured with a trailing one. */
    private static String withoutTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static ProblemDetail identityNotLinked(String userId) {
        return KeriAttestationProblems.unprocessable(KeriAttestationProblems.IDENTITY_NOT_LINKED,
                "User %s has no linked identity to request a credential presentation from.".formatted(userId));
    }

    private static ProblemDetail requestFailed(String detail) {
        return KeriAttestationProblems.unprocessable(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED, detail);
    }

    /** F2 fix: a sync-path guarded update ({@link CeremonyService#updateWaitingStepData}) found the
     *  ceremony no longer waiting on the step it was called for — a concurrent retry/sweep transition
     *  beat this attempt to it. Reported the same way any other stale-state conflict is. */
    private static ProblemDetail staleCeremonyProblem(String ceremonyId) {
        return KeriAttestationProblems.conflict(KeriAttestationProblems.CEREMONY_INVALID_STATE,
                "Ceremony %s is no longer waiting on the expected step.".formatted(ceremonyId));
    }
}
