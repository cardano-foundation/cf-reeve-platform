package org.cardanofoundation.lob.app.keri_attestation.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import com.bloxbean.cardano.client.metadata.MetadataMap;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.CeremonyView;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;

/**
 * Drives the AUTH_BEGIN step (design §4.5) SYNCHRONOUSLY, in the request thread: either verifies a
 * user-supplied external tx hash already establishes on-chain signing authority for the linked AID ("I
 * already have authority" skip), or builds and submits a fresh AUTH_BEGIN transaction from the linked
 * credential chain — then, in BOTH cases, advances the ceremony to {@code AUTH_BEGIN_CONFIRMED}
 * immediately, in this same call.
 *
 * <p><b>"Fire the chain tx and move on" (design rev, user-directed):</b> the own-chain path used to
 * persist the submitted tx hash and dispatch a background poll that waited for N block confirmations
 * before completing the step — the exact kind of background runner this refactor removes. On-chain
 * flows here never wait for confirmation depth at all; they submit and consider the step done. {@link
 * #submitOwn} completes the step the moment {@link
 * CardanoMetadataTxSubmitter#submitMetadataTransaction} returns a tx hash, WITHOUT blocking the request
 * thread waiting for confirmations. (A submitted-but-unconfirmed/rolled-back tx is not specially
 * detected by this class — that is the accepted, deliberate behavior, not a regression introduced
 * here.)
 *
 * <p><b>Return-value convention (deliberate, and different from {@link KeriAttestService}/{@link
 * KeriCredentialService}):</b> per the original design, {@link #submitAuthBegin} returns {@link
 * Either#left} <em>only</em> when the initial {@link CeremonyService#beginStep} guard itself fails (the
 * ceremony never left its prior state, so there is nothing to report via ceremony state). Every failure
 * after that point — no linked identity, an unverifiable external tx, a failed build/submit of a fresh
 * tx — is reported by transitioning the ceremony to {@code FAILED} via {@code failStep} while still
 * returning {@link Either#right} with the resulting (FAILED) {@link CeremonyView}: the request was
 * accepted and processed, and its outcome is visible in the returned ceremony state.
 *
 * <p><b>F9 fix:</b> {@link CardanoMetadataTxSubmitter} is implemented by {@code blockchain_publisher}
 * (design §3.3) — a module that is not guaranteed to be enabled alongside this one. A hard
 * constructor-injected dependency on it would fail this service's bean creation (and therefore this
 * whole module's startup) whenever {@code keri_attestation} is enabled without
 * {@code blockchain_publisher}. It is instead injected as an {@link ObjectProvider} and resolved at
 * each use site ({@link #verifyExternal}, {@link #submitOwn}); when absent, the affected step fails
 * cleanly via {@code failStep} instead of throwing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriAuthBeginService {

    private static final List<Long> AUTH_BEGIN_AUTHORIZED_LABELS = List.of(1447L);
    private static final long AUTH_BEGIN_METADATA_LABEL = 170L;
    private static final String NO_SUBMITTER_DETAIL = "No Cardano transaction submitter available in this deployment.";

    private final KeriAttestationClient client;
    private final CesrChainReducer cesrChainReducer;
    private final Cip170MetadataFactory metadataFactory;
    private final ObjectProvider<CardanoMetadataTxSubmitter> submitterProvider;
    private final CeremonyService ceremonyService;
    private final KeriIdentityLinkRepository identityLinkRepository;
    private final KeriAttestationProperties properties;
    private final CredentialChainValidator chainValidator;

    /**
     * Orchestrates the AUTH_BEGIN step end-to-end, synchronously: {@link CeremonyService#beginStep}
     * from {@code CREDENTIAL_RECEIVED} to {@code AUTH_BEGIN_SUBMITTED}, then either {@link
     * #verifyExternal} or {@link #submitOwn} depending on whether the caller supplied an external tx
     * hash — both complete (or fail) the step in this same call. See the class javadoc for the
     * return-value convention.
     */
    public Either<ProblemDetail, CeremonyView> submitAuthBegin(String ceremonyId, String userId,
            String externalTxHash, boolean retry) {
        Either<ProblemDetail, KeriAttestationCeremonyEntity> begun = ceremonyService.beginStep(ceremonyId, userId,
                CeremonyState.CREDENTIAL_RECEIVED, CeremonyState.AUTH_BEGIN_SUBMITTED, retry);
        if (begun.isLeft()) {
            return Either.left(begun.getLeft());
        }
        KeriAttestationCeremonyEntity ceremony = begun.get();
        int generation = ceremony.getAttemptGeneration();

        Optional<KeriIdentityLinkEntity> linkOpt = identityLinkRepository.findById(userId);
        if (linkOpt.isEmpty() || linkOpt.get().getAid() == null) {
            return failAuthBegin(ceremonyId, userId, generation, KeriAttestationProblems.IDENTITY_NOT_LINKED,
                    "User %s has no linked identity to submit AUTH_BEGIN for.".formatted(userId));
        }
        KeriIdentityLinkEntity link = linkOpt.get();

        if (externalTxHash != null) {
            return verifyExternal(ceremonyId, userId, generation, ceremony, link, externalTxHash);
        }
        return submitOwn(ceremonyId, userId, generation, ceremony, link);
    }

    // --- external authority verification (design §4.5 "the skip") ---

    private Either<ProblemDetail, CeremonyView> verifyExternal(String ceremonyId, String userId, int generation,
            KeriAttestationCeremonyEntity ceremony, KeriIdentityLinkEntity link, String txHash) {
        CardanoMetadataTxSubmitter submitter = submitterProvider.getIfAvailable();
        if (submitter == null) {
            return failAuthBegin(ceremonyId, userId, generation, KeriAttestationProblems.AUTH_BEGIN_UNVERIFIED,
                    NO_SUBMITTER_DETAIL);
        }

        Optional<Map<String, Object>> metadataOpt;
        try {
            metadataOpt = submitter.readCip170Metadata(txHash);
        } catch (Exception e) {
            return failAuthBegin(ceremonyId, userId, generation, KeriAttestationProblems.AUTH_BEGIN_UNVERIFIED,
                    "Failed to read CIP-170 metadata for tx %s: %s".formatted(txHash, e.getMessage()));
        }

        String rejectionReason = validateExternalMetadata(metadataOpt, link, properties.credentialPolicy());
        if (rejectionReason != null) {
            return failAuthBegin(ceremonyId, userId, generation, KeriAttestationProblems.AUTH_BEGIN_UNVERIFIED,
                    rejectionReason);
        }

        String linkUserId = link.getUserId();
        int bindingVersion = ceremony.getBindingVersion();
        Long blockNumber = blockNumberOf(metadataOpt.get());
        try {
            ceremonyService.completeStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                    CeremonyState.AUTH_BEGIN_CONFIRMED,
                    c -> persistAuthBeginIfIdentityStillCurrent(linkUserId, bindingVersion, txHash, blockNumber));
        } catch (Exception e) {
            // The mutator does real JPA work (findById + save) that can throw — unlike every other
            // external-boundary call in this class, completeStep itself has no checked-exception
            // contract to remind us of that. An escaped exception here must not propagate out of
            // submitAuthBegin as an unhandled failure; it must still resolve the ceremony.
            log.warn("Failed to complete AUTH_BEGIN external verification for ceremony {}: {}", ceremonyId,
                    e.getMessage());
            return failAuthBegin(ceremonyId, userId, generation, KeriAttestationProblems.AUTH_BEGIN_UNVERIFIED,
                    "Failed to persist the AUTH_BEGIN confirmation: " + e.getMessage());
        }
        log.info("AUTH_BEGIN externally verified via tx {}, step complete", txHash);
        return ceremonyService.get(ceremonyId, userId);
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

    // --- fresh AUTH_BEGIN submission from the linked credential chain, synchronous fire-and-complete ---

    private Either<ProblemDetail, CeremonyView> submitOwn(String ceremonyId, String userId, int generation,
            KeriAttestationCeremonyEntity ceremony, KeriIdentityLinkEntity link) {
        if (link.getCredentialSaid() == null || link.getCredentialSchemaSaid() == null) {
            return failAuthBegin(ceremonyId, userId, generation, KeriAttestationProblems.IDENTITY_NOT_LINKED,
                    "User %s has no validated credential to build an AUTH_BEGIN chain from."
                            .formatted(link.getUserId()));
        }

        CardanoMetadataTxSubmitter submitter = submitterProvider.getIfAvailable();
        if (submitter == null) {
            return failAuthBegin(ceremonyId, userId, generation,
                    KeriAttestationProblems.AUTH_BEGIN_SUBMISSION_UNAVAILABLE, NO_SUBMITTER_DETAIL);
        }

        String txHash;
        try {
            Optional<String> cesrOpt = client.client().credentials().get(link.getCredentialSaid());
            if (cesrOpt.isEmpty()) {
                return failAuthBegin(ceremonyId, userId, generation, KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED,
                        "Credential %s was not found in the credential store.".formatted(link.getCredentialSaid()));
            }
            String fullCesr = cesrOpt.get();

            // Reusable-attestation design rev: re-validate the full chain here too, not just at
            // credential-presentation time (KeriCredentialService#presentCredential) — the same gate,
            // applied again right before this identity's credential chain is published on-chain. See
            // CredentialChainValidator's class javadoc for what is (and, for now, is not) enforced.
            Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> validated = chainValidator.validate(
                    fullCesr, link.getAid(), properties.credentialPolicy().schemaSaids(),
                    properties.credentialPolicy().trustedRootAids());
            if (validated.isLeft()) {
                return failAuthBegin(ceremonyId, userId, generation, KeriAttestationProblems.CREDENTIAL_REJECTED,
                        validated.getLeft().getDetail());
            }

            byte[] reducedChain = cesrChainReducer.reduceToVcpIssAcdc(fullCesr);
            MetadataMap map = metadataFactory.authBeginMap(link.getAid(), link.getCredentialSchemaSaid(),
                    reducedChain, null, AUTH_BEGIN_AUTHORIZED_LABELS);

            Either<ProblemDetail, String> submitResult =
                    submitter.submitMetadataTransaction(AUTH_BEGIN_METADATA_LABEL, map);
            if (submitResult.isLeft()) {
                ProblemDetail problem = submitResult.getLeft();
                return failAuthBegin(ceremonyId, userId, generation, problem.getTitle(), problem.getDetail());
            }
            txHash = submitResult.get();
            log.info("AUTH_BEGIN tx submitted {}", txHash);
        } catch (Exception e) {
            interruptIfNeeded(e);
            log.warn("Failed to build/submit AUTH_BEGIN for ceremony {}: {}", ceremonyId, e.getMessage());
            return failAuthBegin(ceremonyId, userId, generation, KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED,
                    "Failed to build/submit the AUTH_BEGIN transaction: " + e.getMessage());
        }

        // Complete the step the moment the tx is submitted — no wait for confirmation depth (see class
        // javadoc). The link write is folded into completeStep's mutator, same idiom as
        // KeriCredentialService#persistCredentialIfIdentityStillCurrent.
        String finalTxHash = txHash;
        int bindingVersion = ceremony.getBindingVersion();
        try {
            boolean completed = ceremonyService.completeStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                    CeremonyState.AUTH_BEGIN_CONFIRMED,
                    c -> persistAuthBeginIfIdentityStillCurrent(userId, bindingVersion, finalTxHash, null));
            if (!completed) {
                // Stale CAS (a concurrent retry superseded this attempt) — the tx is already submitted
                // on-chain, but this attempt is no longer the current one; the winning attempt's own
                // state is authoritative, so this just reports whatever that ended up being rather than
                // clobbering it via failStep.
                log.warn("Skipping AUTH_BEGIN completion for ceremony {}: no longer waiting on "
                        + "AUTH_BEGIN_SUBMITTED.", ceremonyId);
                return ceremonyService.get(ceremonyId, userId);
            }
        } catch (Exception e) {
            // completeStep's mutator does real JPA work that can throw; an escaped exception here must
            // not propagate out of submitAuthBegin, it must still resolve the ceremony.
            log.warn("Failed to complete AUTH_BEGIN submission for ceremony {}: {}", ceremonyId, e.getMessage());
            return failAuthBegin(ceremonyId, userId, generation, KeriAttestationProblems.AUTH_BEGIN_ROLLED_BACK,
                    "Failed to persist the AUTH_BEGIN confirmation: " + e.getMessage());
        }
        log.info("AUTH_BEGIN step complete");
        return ceremonyService.get(ceremonyId, userId);
    }

    // --- internals ---

    private Either<ProblemDetail, CeremonyView> failAuthBegin(String ceremonyId, String userId, int generation,
            String title, String detail) {
        ceremonyService.failStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED, title, detail);
        return ceremonyService.get(ceremonyId, userId);
    }

    /**
     * Persists the confirmed (or verified-external) AUTH_BEGIN tx hash to the identity link.
     * <b>Only ever called from inside a {@link CeremonyService#completeStep} mutator</b> — that method
     * only invokes its mutator after the ceremony row's own {@code (state, attemptGeneration)} CAS has
     * already confirmed this is the current, non-superseded attempt, so a stale generation's mutator
     * never runs at all, and both the link write and the ceremony transition commit together in
     * {@code CeremonyService}'s one transaction.
     *
     * <p>That CAS alone does not cover a relink, though: relink doesn't touch the ceremony's own
     * {@code (state, attemptGeneration)} directly (a separate {@code KeriOobiService} write invalidates
     * the ceremony out-of-band), so this re-fetches the link fresh and compares {@code bindingVersion}
     * against the version this attempt was authorized under — the same idiom
     * {@code KeriCredentialService#persistCredentialIfIdentityStillCurrent} uses for the identical race.
     *
     * <p>The re-fetch is row-locked (F3 fix) via
     * {@link org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository#findByUserIdForUpdate}
     * rather than a plain {@code findById}: this write and {@code KeriOobiService}'s relink write race
     * the same row, and without the lock the two could interleave into a row that is a mix of the old
     * and new identity.
     */
    private void persistAuthBeginIfIdentityStillCurrent(String userId, int expectedBindingVersion, String txHash,
            Long blockNumber) {
        identityLinkRepository.findByUserIdForUpdate(userId).ifPresent(freshLink -> {
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
