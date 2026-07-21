package org.cardanofoundation.lob.app.keri_attestation.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import com.bloxbean.cardano.client.metadata.MetadataMap;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.signify.app.clienting.SignifyClient;

/**
 * Drives the AUTH_BEGIN step (design §4.5): either verifies a user-supplied external tx hash already
 * establishes on-chain signing authority for the linked AID ("I already have authority" skip), or
 * builds and submits a fresh AUTH_BEGIN transaction from the linked credential chain, then — on the
 * async continuation, {@link #awaitAuthBeginConfirmation} — polls for confirmation depth before
 * advancing the ceremony to {@code AUTH_BEGIN_CONFIRMED}.
 *
 * <p><b>Return-value convention (deliberate, and different from {@link KeriAttestService}):</b> per
 * the brief, {@link #submitAuthBegin} returns {@link Either#left} <em>only</em> when the initial
 * {@link CeremonyService#beginStep} guard itself fails (the ceremony never left its prior state, so
 * there is nothing to report via ceremony state). Every failure after that point — no linked identity,
 * an unverifiable external tx, a failed build/submit of a fresh tx — is reported by transitioning the
 * ceremony to {@code FAILED} via {@code failStep} while still returning {@link Either#right}: the
 * request was accepted and processed, and its outcome is visible via ceremony polling (design §4.2
 * "the triggering POST returns 202 immediately and the frontend polls"), exactly like the wallet-wait
 * steps ({@link KeriCredentialService#awaitPresentation}, {@link KeriAttestService#awaitAnchor}) never
 * return anything to a caller at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriAuthBeginService {

    private static final List<Long> AUTH_BEGIN_AUTHORIZED_LABELS = List.of(1447L);
    private static final long AUTH_BEGIN_METADATA_LABEL = 170L;

    @Qualifier("keriAttestationSignifyClient")
    private final SignifyClient client;
    private final CesrChainReducer cesrChainReducer;
    private final Cip170MetadataFactory metadataFactory;
    private final CardanoMetadataTxSubmitter submitter;
    private final CeremonyService ceremonyService;
    private final KeriAttestationCeremonyRepository ceremonyRepository;
    private final KeriIdentityLinkRepository identityLinkRepository;
    private final KeriAttestationProperties properties;
    private final CeremonyAsyncRunner asyncRunner;

    // --- synchronous: verify external authority, or build + submit a fresh AUTH_BEGIN tx ---

    public Either<ProblemDetail, Void> submitAuthBegin(String ceremonyId, String userId, String externalTxHash,
            boolean retry) {
        Either<ProblemDetail, KeriAttestationCeremonyEntity> begun = ceremonyService.beginStep(ceremonyId, userId,
                CeremonyState.CREDENTIAL_RECEIVED, CeremonyState.AUTH_BEGIN_SUBMITTED, retry);
        if (begun.isLeft()) {
            return Either.left(begun.getLeft());
        }
        KeriAttestationCeremonyEntity ceremony = begun.get();
        int generation = ceremony.getAttemptGeneration();

        Optional<KeriIdentityLinkEntity> linkOpt = identityLinkRepository.findById(userId);
        if (linkOpt.isEmpty() || linkOpt.get().getAid() == null) {
            ceremonyService.failStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                    KeriAttestationProblems.IDENTITY_NOT_LINKED,
                    "User %s has no linked identity to submit AUTH_BEGIN for.".formatted(userId));
            return Either.right(null);
        }
        KeriIdentityLinkEntity link = linkOpt.get();

        if (externalTxHash != null) {
            verifyExternal(ceremonyId, generation, ceremony, link, externalTxHash);
        } else {
            submitOwn(ceremonyId, generation, ceremony, link);
        }
        return Either.right(null);
    }

    // --- external authority verification (design §4.5 "the skip") ---

    private void verifyExternal(String ceremonyId, int generation, KeriAttestationCeremonyEntity ceremony,
            KeriIdentityLinkEntity link, String txHash) {
        Optional<Map<String, Object>> metadataOpt;
        try {
            metadataOpt = submitter.readCip170Metadata(txHash);
        } catch (Exception e) {
            ceremonyService.failStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                    KeriAttestationProblems.AUTH_BEGIN_UNVERIFIED,
                    "Failed to read CIP-170 metadata for tx %s: %s".formatted(txHash, e.getMessage()));
            return;
        }

        String rejectionReason = validateExternalMetadata(metadataOpt, link, properties.credentialPolicy());
        if (rejectionReason != null) {
            ceremonyService.failStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                    KeriAttestationProblems.AUTH_BEGIN_UNVERIFIED, rejectionReason);
            return;
        }

        String userId = link.getUserId();
        int bindingVersion = ceremony.getBindingVersion();
        Long blockNumber = blockNumberOf(metadataOpt.get());
        try {
            ceremonyService.completeStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                    CeremonyState.AUTH_BEGIN_CONFIRMED,
                    c -> persistAuthBeginIfIdentityStillCurrent(userId, bindingVersion, txHash, blockNumber));
        } catch (Exception e) {
            // The mutator does real JPA work (findById + save) that can throw — unlike every other
            // external-boundary call in this class, completeStep itself has no checked-exception
            // contract to remind us of that. An escaped exception here must not propagate out of
            // submitAuthBegin as an unhandled failure; it must still resolve the ceremony.
            log.warn("Failed to complete AUTH_BEGIN external verification for ceremony {}: {}", ceremonyId,
                    e.getMessage());
            ceremonyService.failStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                    KeriAttestationProblems.AUTH_BEGIN_UNVERIFIED,
                    "Failed to persist the AUTH_BEGIN confirmation: " + e.getMessage());
        }
    }

    private static Long blockNumberOf(Map<String, Object> metadata) {
        Object block = metadata.get("block");
        return block instanceof Number number ? number.longValue() : null;
    }

    /** @return a human-readable rejection reason, or {@code null} if the metadata verifies. */
    private static String validateExternalMetadata(Optional<Map<String, Object>> metadataOpt,
            KeriIdentityLinkEntity link, KeriAttestationProperties.CredentialPolicy credentialPolicy) {
        if (metadataOpt.isEmpty()) {
            return "No label-170 metadata was found on-chain for the given tx hash.";
        }
        Map<String, Object> metadata = metadataOpt.get();
        if (!"AUTH_BEGIN".equals(metadata.get("t"))) {
            return "On-chain label-170 metadata is not an AUTH_BEGIN entry (t=%s).".formatted(metadata.get("t"));
        }
        if (!link.getAid().equals(metadata.get("i"))) {
            return "On-chain AUTH_BEGIN issuer AID (%s) does not match the linked identity (%s)."
                    .formatted(metadata.get("i"), link.getAid());
        }
        List<String> allowedSchemas = credentialPolicy != null ? credentialPolicy.schemaSaids() : null;
        Object schema = metadata.get("s");
        if (!(schema instanceof String schemaSaid) || allowedSchemas == null || !allowedSchemas.contains(schemaSaid)) {
            return "On-chain AUTH_BEGIN schema (%s) is not in the allowed schema list.".formatted(schema);
        }
        return null;
    }

    // --- fresh AUTH_BEGIN submission from the linked credential chain ---

    private void submitOwn(String ceremonyId, int generation, KeriAttestationCeremonyEntity ceremony,
            KeriIdentityLinkEntity link) {
        if (link.getCredentialSaid() == null || link.getCredentialSchemaSaid() == null) {
            ceremonyService.failStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                    KeriAttestationProblems.IDENTITY_NOT_LINKED,
                    "User %s has no validated credential to build an AUTH_BEGIN chain from."
                            .formatted(link.getUserId()));
            return;
        }

        String txHash;
        try {
            Optional<String> cesrOpt = client.credentials().get(link.getCredentialSaid());
            if (cesrOpt.isEmpty()) {
                ceremonyService.failStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                        KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED,
                        "Credential %s was not found in the credential store.".formatted(link.getCredentialSaid()));
                return;
            }

            byte[] reducedChain = cesrChainReducer.reduceToVcpIssAcdc(cesrOpt.get());
            MetadataMap map = metadataFactory.authBeginMap(link.getAid(), link.getCredentialSchemaSaid(),
                    reducedChain, null, AUTH_BEGIN_AUTHORIZED_LABELS);

            Either<ProblemDetail, String> submitResult =
                    submitter.submitMetadataTransaction(AUTH_BEGIN_METADATA_LABEL, map);
            if (submitResult.isLeft()) {
                ProblemDetail problem = submitResult.getLeft();
                ceremonyService.failStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                        problem.getTitle(), problem.getDetail());
                return;
            }
            txHash = submitResult.get();
        } catch (Exception e) {
            interruptIfNeeded(e);
            log.warn("Failed to build/submit AUTH_BEGIN for ceremony {}: {}", ceremonyId, e.getMessage());
            ceremonyService.failStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                    KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED,
                    "Failed to build/submit the AUTH_BEGIN transaction: " + e.getMessage());
            return;
        }

        ceremony.setAuthBeginTxHash(txHash);
        ceremonyRepository.save(ceremony);

        try {
            asyncRunner.awaitAuthBeginConfirmation(ceremonyId, generation);
        } catch (Exception e) {
            // The executor rejected the dispatch (pool/queue saturated) — the tx is already submitted
            // on-chain, but with no worker left to watch it, the ceremony must not sit non-terminal
            // with an unhandled exception as the only signal. FAILED(AUTH_BEGIN_ROLLED_BACK) matches the
            // "resubmittable" story a genuine rollback would get; a retry submits a fresh tx.
            log.warn("Failed to dispatch AUTH_BEGIN confirmation wait for ceremony {}: {}", ceremonyId,
                    e.getMessage());
            ceremonyService.failStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                    KeriAttestationProblems.AUTH_BEGIN_ROLLED_BACK,
                    "Failed to dispatch the AUTH_BEGIN confirmation wait: " + e.getMessage());
        }
    }

    // --- asynchronous continuation: poll for confirmations, complete or roll back ---

    /**
     * Runs unsupervised on {@link CeremonyAsyncRunner}'s background executor — see
     * {@link KeriCredentialService#awaitPresentation}'s javadoc for the "must always resolve, never
     * propagate" rationale this method follows too. Polls
     * {@link KeriAttestationProperties#authBeginPollInterval()} until
     * {@link CardanoMetadataTxSubmitter#confirmations} reaches
     * {@link KeriAttestationProperties#authBeginConfirmations()}, or
     * {@link KeriAttestationProperties#authBeginRollbackWindow()} elapses without that happening —
     * {@code FAILED(AUTH_BEGIN_ROLLED_BACK)}, resubmittable per design §4.5.
     */
    public void awaitAuthBeginConfirmation(String ceremonyId, int generation) {
        Optional<KeriAttestationCeremonyEntity> ceremonyOpt = ceremonyRepository.findById(ceremonyId);
        if (ceremonyOpt.isEmpty()) {
            return;
        }
        KeriAttestationCeremonyEntity ceremony = ceremonyOpt.get();
        String txHash = ceremony.getAuthBeginTxHash();
        if (txHash == null) {
            ceremonyService.failStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                    KeriAttestationProblems.AUTH_BEGIN_ROLLED_BACK,
                    "No pending AUTH_BEGIN transaction hash was recorded to confirm.");
            return;
        }

        Instant deadline = Instant.now().plus(properties.authBeginRollbackWindow());
        while (true) {
            Optional<Long> confirmations;
            try {
                confirmations = submitter.confirmations(txHash);
            } catch (Exception e) {
                log.warn("Failed to check confirmations for AUTH_BEGIN tx {}: {}", txHash, e.getMessage());
                confirmations = Optional.empty();
            }

            if (confirmations.isPresent() && confirmations.get() >= properties.authBeginConfirmations()) {
                String userId = ceremony.getUserId();
                int bindingVersion = ceremony.getBindingVersion();
                try {
                    ceremonyService.completeStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                            CeremonyState.AUTH_BEGIN_CONFIRMED,
                            c -> persistAuthBeginIfIdentityStillCurrent(userId, bindingVersion, txHash, null));
                } catch (Exception e) {
                    // Same rationale as verifyExternal's guard: the mutator's JPA work can throw, and
                    // this is an unsupervised async worker — it must resolve the ceremony, never
                    // propagate and leave it stuck at AUTH_BEGIN_SUBMITTED for the TTL sweep alone to
                    // eventually catch.
                    log.warn("Failed to complete AUTH_BEGIN confirmation for ceremony {}: {}", ceremonyId,
                            e.getMessage());
                    ceremonyService.failStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                            KeriAttestationProblems.AUTH_BEGIN_ROLLED_BACK,
                            "Failed to persist the AUTH_BEGIN confirmation: " + e.getMessage());
                }
                return;
            }

            if (!Instant.now().isBefore(deadline)) {
                ceremonyService.failStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                        KeriAttestationProblems.AUTH_BEGIN_ROLLED_BACK,
                        "Tx %s did not reach %d confirmations within the rollback window.".formatted(txHash,
                                properties.authBeginConfirmations()));
                return;
            }

            Duration pollInterval = properties.authBeginPollInterval();
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // --- internals ---

    /**
     * Persists the confirmed (or verified-external) AUTH_BEGIN tx hash to the identity link.
     * <b>Only ever called from inside a {@link CeremonyService#completeStep} mutator</b> — that method
     * only invokes its mutator after the ceremony row's own {@code (state, attemptGeneration)} CAS has
     * already confirmed this is the current, non-superseded attempt (design §4.2), so a stale
     * generation's mutator (e.g. a retry raced by a slower earlier worker) never runs at all, and both
     * the link write and the ceremony transition commit together in {@code CeremonyService}'s one
     * transaction.
     *
     * <p>That CAS alone does not cover a relink, though: relink doesn't touch the ceremony's own
     * {@code (state, attemptGeneration)} directly (a separate {@code KeriOobiService} write fails the
     * ceremony out-of-band), so this re-fetches the link fresh and compares {@code bindingVersion}
     * against the version this attempt was authorized under — the same idiom
     * {@code KeriCredentialService#awaitPresentation} uses for the identical race (a relink landing
     * mid-flight must never let a stale write re-attach authority data to what is now a different
     * identity).
     */
    private void persistAuthBeginIfIdentityStillCurrent(String userId, int expectedBindingVersion, String txHash,
            Long blockNumber) {
        identityLinkRepository.findById(userId).ifPresent(freshLink -> {
            if (freshLink.getBindingVersion() != expectedBindingVersion) {
                log.warn("Skipping AUTH_BEGIN link write for user {}: identity was relinked (expected binding "
                        + "version {}, now {}).", userId, expectedBindingVersion, freshLink.getBindingVersion());
                return;
            }
            freshLink.setAuthBeginTxHash(txHash);
            freshLink.setAuthBeginAt(Instant.now());
            if (blockNumber != null) {
                freshLink.setAuthBeginBlock(blockNumber);
            }
            identityLinkRepository.save(freshLink);
        });
    }

    private static void interruptIfNeeded(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
