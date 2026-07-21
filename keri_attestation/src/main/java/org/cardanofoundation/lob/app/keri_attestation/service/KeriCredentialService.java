package org.cardanofoundation.lob.app.keri_attestation.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAdmitArgs;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAgreeArgs;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexApplyArgs;

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
 * actually calls: {@code beginStep} + a retry pre-check + {@link #startPresentation} +
 * {@link CeremonyAsyncRunner} dispatch, mirroring {@link KeriAttestService#startAttest}'s shape exactly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriCredentialService {

    private static final List<String> OFFER_ROUTES = List.of("/exn/ipex/offer");
    private static final List<String> GRANT_ROUTES = List.of("/exn/ipex/grant");

    /** Short wait for the retry pre-check (design §4.2 "before re-sending... checks for a
     *  late-arriving matching notification") — mirrors {@link KeriAttestService}'s own retry-precheck
     *  timeout, applied here to the credential-presentation step instead of the ATTEST anchor. */
    private static final Duration RETRY_PRECHECK_TIMEOUT = Duration.ofSeconds(2);

    private final KeriAttestationClient client;
    private final KeriAgentService agentService;
    private final KeriNotificationCorrelator correlator;
    private final CredentialChainValidator validator;
    private final CeremonyService ceremonyService;
    private final KeriAttestationCeremonyRepository ceremonyRepository;
    private final KeriIdentityLinkRepository identityLinkRepository;
    private final KeriAttestationProperties properties;
    private final CeremonyAsyncRunner asyncRunner;

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
        String linkedAid = linkOpt.get().getAid();

        // Retry pre-check (design §4.2, mirrors KeriAttestService#startAttest): before sending a fresh
        // IPEX apply, look for a late-arriving offer correlated to the PREVIOUS attempt's apply. Found:
        // skip straight to dispatching the async continuation — its own (non-destructive)
        // correlator.awaitCorrelated call will find the same offer again, so nothing needs re-sending.
        // Not found (or this is the first attempt, requestExnSaid still null): fall through and build +
        // send a fresh apply below.
        if (retry && ceremony.getRequestExnSaid() != null) {
            Optional<CorrelatedNotification> lateOffer = correlator.awaitCorrelated(OFFER_ROUTES, linkedAid,
                    ceremony.getRequestExnSaid(), RETRY_PRECHECK_TIMEOUT);
            if (lateOffer.isPresent()) {
                return dispatchAwaitPresentation(ceremonyId, generation);
            }
        }

        Either<ProblemDetail, Void> sent = startPresentation(ceremony);
        if (sent.isLeft()) {
            ProblemDetail problem = sent.getLeft();
            ceremonyService.failStep(ceremonyId, generation, CeremonyState.CREDENTIAL_REQUESTED, problem.getTitle(),
                    problem.getDetail());
            return Either.left(problem);
        }

        return dispatchAwaitPresentation(ceremonyId, generation);
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
        ceremonyService.failStep(ceremonyId, generation, CeremonyState.CREDENTIAL_REQUESTED, title, detail);
        return Either.left(KeriAttestationProblems.unprocessable(title, detail));
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

            IpexApplyArgs applyArgs = IpexApplyArgs.builder()
                    .senderName(agentName)
                    .recipient(linkedAid)
                    .message("")
                    .schemaSaid(schemaSaid)
                    .attributes(Map.of("oobiUrl", agentService.agentOobi()))
                    .build();
            ExchangeMessageResult applyResult = client.client().ipex().apply(applyArgs);
            String exnSaid = (String) applyResult.exn().getKed().get("d");

            // Persist BEFORE the send completes (design §4.6 pattern applied here too): the SAID is
            // deterministic from the built (unsent) exn, so if the network call below fails partway
            // through, the ceremony still records what was — or was about to be — sent, and a retry can
            // check for a late-arriving correlated reply before re-sending.
            ceremony.setRequestExnSaid(exnSaid);
            ceremonyRepository.save(ceremony);

            client.client().ipex().submitApply(agentName, applyResult.exn(), applyResult.sigs(), List.of(linkedAid));
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
     */
    public void awaitPresentation(String ceremonyId, int expectedGeneration) {
        Optional<KeriAttestationCeremonyEntity> ceremonyOpt = ceremonyRepository.findById(ceremonyId);
        if (ceremonyOpt.isEmpty()) {
            return;
        }
        KeriAttestationCeremonyEntity ceremony = ceremonyOpt.get();

        Optional<KeriIdentityLinkEntity> linkOpt = identityLinkRepository.findById(ceremony.getUserId());
        if (linkOpt.isEmpty() || linkOpt.get().getAid() == null) {
            ceremonyService.failStep(ceremonyId, expectedGeneration, CeremonyState.CREDENTIAL_REQUESTED,
                    KeriAttestationProblems.IDENTITY_NOT_LINKED,
                    "User %s has no linked identity to await a presentation from.".formatted(ceremony.getUserId()));
            return;
        }
        String linkedAid = linkOpt.get().getAid();
        String agentName = agentService.agentName();

        Optional<CorrelatedNotification> offer = correlator.awaitCorrelated(OFFER_ROUTES, linkedAid,
                ceremony.getRequestExnSaid(), properties.remotesignTimeout());
        if (offer.isEmpty()) {
            failTimeout(ceremonyId, expectedGeneration, "Timed out waiting for /exn/ipex/offer.");
            return;
        }

        String agreeSaid;
        try {
            ExchangeMessageResult agreeResult = client.client().ipex().agree(IpexAgreeArgs.builder()
                    .senderName(agentName).recipient(linkedAid).message("")
                    .offerSaid(offer.get().exnSaid()).build());
            agreeSaid = (String) agreeResult.exn().getKed().get("d");
            client.client().ipex().submitAgree(agentName, agreeResult.exn(), agreeResult.sigs(), List.of(linkedAid));
        } catch (Exception e) {
            interruptIfNeeded(e);
            failRequest(ceremonyId, expectedGeneration, "Failed to send IPEX agree: " + e.getMessage());
            return;
        }

        Optional<CorrelatedNotification> grant = correlator.awaitCorrelated(GRANT_ROUTES, linkedAid, agreeSaid,
                properties.remotesignTimeout());
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
                    .grantSaid(grant.get().exnSaid()).build());
            client.client().ipex().submitAdmit(agentName, admitResult.exn(), admitResult.sigs(), admitResult.atc(),
                    List.of(linkedAid));
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
            ceremonyService.failStep(ceremonyId, expectedGeneration, CeremonyState.CREDENTIAL_REQUESTED,
                    KeriAttestationProblems.CREDENTIAL_REJECTED, "Chain validation error: " + e.getMessage());
            return;
        }
        if (validated.isLeft()) {
            ceremonyService.failStep(ceremonyId, expectedGeneration, CeremonyState.CREDENTIAL_REQUESTED,
                    KeriAttestationProblems.CREDENTIAL_REJECTED, validated.getLeft().getDetail());
            return;
        }
        ValidatedCredential vc = validated.get();

        // Defense-in-depth: the validator finds its leaf by issuee match, independently of the SAID we
        // fetched the stream for — they must agree. A mismatch would mean the presented stream's
        // issuee-matching leaf isn't the credential the grant/admit round trip was actually about,
        // which should be structurally impossible but is cheap to assert outright rather than trust.
        if (!credentialSaid.equals(vc.credentialSaid())) {
            ceremonyService.failStep(ceremonyId, expectedGeneration, CeremonyState.CREDENTIAL_REQUESTED,
                    KeriAttestationProblems.CREDENTIAL_REJECTED,
                    "Validated leaf credential %s does not match the fetched credential %s."
                            .formatted(vc.credentialSaid(), credentialSaid));
            return;
        }

        // Fold the link write into completeStep's mutator (F5 fix — mirrors
        // KeriAuthBeginService#persistAuthBeginIfIdentityStillCurrent exactly): the credential write and
        // the ceremony's CREDENTIAL_REQUESTED -> CREDENTIAL_RECEIVED transition must commit atomically,
        // in CeremonyService's one transaction. The old code saved the link in its own separate
        // transaction before ever calling completeStep, so a stale CAS below (a retry superseded this
        // attempt) still left the link durably written even though the ceremony transition it belongs
        // with never happened.
        String userId = ceremony.getUserId();
        int bindingVersion = ceremony.getBindingVersion();
        String finalCredentialSaid = vc.credentialSaid();
        String finalSchemaSaid = vc.schemaSaid();

        boolean completed = ceremonyService.completeStep(ceremonyId, expectedGeneration,
                CeremonyState.CREDENTIAL_REQUESTED, CeremonyState.CREDENTIAL_RECEIVED,
                c -> persistCredentialIfIdentityStillCurrent(userId, bindingVersion, finalCredentialSaid,
                        finalSchemaSaid));
        if (!completed) {
            // Stale CAS (a retry superseded this attempt) — the link must not be written either (the
            // mutator above never runs), and the notifications must be left alone: the winning attempt's
            // own correlator wait is (or will be) matching against the same requests and needs to find
            // them still unread/undeleted.
            return;
        }

        // Only after both the link and the ceremony transition are durably committed: an earlier
        // mark-and-delete would let a crash between the two silently lose the wallet's replies, exactly
        // the failure mode KeriNotificationCorrelator#markAndDelete's contract exists to prevent.
        correlator.markAndDelete(offer.get().notificationId());
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
     */
    private void persistCredentialIfIdentityStillCurrent(String userId, int expectedBindingVersion,
            String credentialSaid, String credentialSchemaSaid) {
        identityLinkRepository.findById(userId).ifPresent(freshLink -> {
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

    private void failTimeout(String ceremonyId, int expectedGeneration, String detail) {
        ceremonyService.failStep(ceremonyId, expectedGeneration, CeremonyState.CREDENTIAL_REQUESTED,
                KeriAttestationProblems.KERI_WALLET_TIMEOUT, detail);
    }

    private void failRequest(String ceremonyId, int expectedGeneration, String detail) {
        log.warn("Credential presentation failed for ceremony {}: {}", ceremonyId, detail);
        ceremonyService.failStep(ceremonyId, expectedGeneration, CeremonyState.CREDENTIAL_REQUESTED,
                KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED, detail);
    }

    private static void interruptIfNeeded(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static ProblemDetail identityNotLinked(String userId) {
        return KeriAttestationProblems.unprocessable(KeriAttestationProblems.IDENTITY_NOT_LINKED,
                "User %s has no linked identity to request a credential presentation from.".formatted(userId));
    }

    private static ProblemDetail requestFailed(String detail) {
        return KeriAttestationProblems.unprocessable(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED, detail);
    }
}
