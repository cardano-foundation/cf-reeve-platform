package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.CredentialChainValidator.ValidatedCredential;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriNotificationCorrelator.CorrelatedNotification;
import org.cardanofoundation.signify.app.Exchanging.ExchangeMessageResult;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
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
 * returns (Task 9 wires the executor — this method is unit-tested directly for now).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriCredentialService {

    private static final List<String> OFFER_ROUTES = List.of("/exn/ipex/offer");
    private static final List<String> GRANT_ROUTES = List.of("/exn/ipex/grant");

    @Qualifier("keriAttestationSignifyClient")
    private final SignifyClient client;
    private final KeriAgentService agentService;
    private final KeriNotificationCorrelator correlator;
    private final CredentialChainValidator validator;
    private final CeremonyService ceremonyService;
    private final KeriAttestationCeremonyRepository ceremonyRepository;
    private final KeriIdentityLinkRepository identityLinkRepository;
    private final KeriAttestationProperties properties;

    // --- synchronous: build + send the apply, persist requestExnSaid before the send completes ---

    public Either<ProblemDetail, Void> startPresentation(KeriAttestationCeremonyEntity ceremony) {
        Optional<KeriIdentityLinkEntity> linkOpt = identityLinkRepository.findById(ceremony.getUserId());
        if (linkOpt.isEmpty() || linkOpt.get().getAid() == null) {
            return Either.left(identityNotLinked(ceremony.getUserId()));
        }
        String linkedAid = linkOpt.get().getAid();
        String schemaSaid = firstAllowedSchema();
        String agentName = agentService.agentName();

        try {
            IpexApplyArgs applyArgs = IpexApplyArgs.builder()
                    .senderName(agentName)
                    .recipient(linkedAid)
                    .message("")
                    .schemaSaid(schemaSaid)
                    .attributes(Map.of("oobiUrl", agentService.agentOobi()))
                    .build();
            ExchangeMessageResult applyResult = client.ipex().apply(applyArgs);
            String exnSaid = (String) applyResult.exn().getKed().get("d");

            // Persist BEFORE the send completes (design §4.6 pattern applied here too): the SAID is
            // deterministic from the built (unsent) exn, so if the network call below fails partway
            // through, the ceremony still records what was — or was about to be — sent, and a retry can
            // check for a late-arriving correlated reply before re-sending.
            ceremony.setRequestExnSaid(exnSaid);
            ceremonyRepository.save(ceremony);

            client.ipex().submitApply(agentName, applyResult.exn(), applyResult.sigs(), List.of(linkedAid));
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
            ExchangeMessageResult agreeResult = client.ipex().agree(IpexAgreeArgs.builder()
                    .senderName(agentName).recipient(linkedAid).message("")
                    .offerSaid(offer.get().exnSaid()).build());
            agreeSaid = (String) agreeResult.exn().getKed().get("d");
            client.ipex().submitAgree(agentName, agreeResult.exn(), agreeResult.sigs(), List.of(linkedAid));
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
            ExchangeMessageResult admitResult = client.ipex().admit(IpexAdmitArgs.builder()
                    .senderName(agentName).recipient(linkedAid).message("")
                    .grantSaid(grant.get().exnSaid()).build());
            client.ipex().submitAdmit(agentName, admitResult.exn(), admitResult.sigs(), admitResult.atc(),
                    List.of(linkedAid));
        } catch (Exception e) {
            interruptIfNeeded(e);
            failRequest(ceremonyId, expectedGeneration, "Failed to admit IPEX grant: " + e.getMessage());
            return;
        }

        String fullCesr;
        try {
            Optional<String> cesrOpt = client.credentials().get(credentialSaid);
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

        Either<ProblemDetail, ValidatedCredential> validated = validator.validate(fullCesr, linkedAid,
                properties.credentialPolicy().schemaSaids(), properties.credentialPolicy().trustedRootAids());
        if (validated.isLeft()) {
            ceremonyService.failStep(ceremonyId, expectedGeneration, CeremonyState.CREDENTIAL_REQUESTED,
                    KeriAttestationProblems.CREDENTIAL_REJECTED, validated.getLeft().getDetail());
            return;
        }

        // Re-fetch the link fresh rather than reusing linkOpt: a relink racing this whole round trip
        // would have changed the AID (and bumped bindingVersion) since we started, and this must never
        // attach a freshly-validated credential to the *old* AID's row.
        Optional<KeriIdentityLinkEntity> freshLinkOpt = identityLinkRepository.findById(ceremony.getUserId());
        if (freshLinkOpt.isEmpty() || !linkedAid.equals(freshLinkOpt.get().getAid())) {
            ceremonyService.failStep(ceremonyId, expectedGeneration, CeremonyState.CREDENTIAL_REQUESTED,
                    KeriAttestationProblems.IDENTITY_RELINKED,
                    "Identity for user %s changed while awaiting the credential presentation."
                            .formatted(ceremony.getUserId()));
            return;
        }

        ValidatedCredential vc = validated.get();
        KeriIdentityLinkEntity link = freshLinkOpt.get();
        link.setCredentialSaid(vc.credentialSaid());
        link.setCredentialSchemaSaid(vc.schemaSaid());
        identityLinkRepository.save(link);

        ceremonyService.completeStep(ceremonyId, expectedGeneration, CeremonyState.CREDENTIAL_REQUESTED,
                CeremonyState.CREDENTIAL_RECEIVED, c -> { /* nothing extra to persist on the ceremony row */ });

        // Only after both the link and the ceremony transition are durably committed: an earlier
        // mark-and-delete would let a crash between the two silently lose the wallet's replies, exactly
        // the failure mode KeriNotificationCorrelator#markAndDelete's contract exists to prevent.
        correlator.markAndDelete(offer.get().notificationId());
        correlator.markAndDelete(grant.get().notificationId());
    }

    // --- internals ---

    private String firstAllowedSchema() {
        List<String> schemaSaids = properties.credentialPolicy().schemaSaids();
        if (schemaSaids == null || schemaSaids.isEmpty()) {
            throw new IllegalStateException(
                    "No schema SAIDs configured under lob.keri-attestation.credential-policy.schema-saids.");
        }
        return schemaSaids.get(0);
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
