package org.cardanofoundation.lob.app.keri_attestation.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.AuthBeginPublishCommand;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.CeremonyView;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.signify.exception.SignifyInterruptedException;

/**
 * Drives the AUTH_BEGIN step. This module never touches the chain: it validates the linked credential
 * chain, reduces it, and hands an {@link AuthBeginPublishCommand} to {@code blockchain_publisher},
 * which builds, signs and submits the CIP-170 transaction through the same dispatcher every other
 * publishable type uses. That is what lets the API tier run with no wallet, no chain reader and no
 * transaction submitter of any kind.
 *
 * <p>The step is therefore ASYNCHRONOUS. {@link #submitAuthBegin} leaves the ceremony resting in
 * {@code AUTH_BEGIN_SUBMITTED}; {@code AuthBeginLedgerUpdateHandler} advances it to
 * {@code AUTH_BEGIN_CONFIRMED} when the publisher reports the transaction dispatched. A ceremony whose
 * publish never lands is failed by {@code CeremonyCleanupJob}'s stale-step sweep, budgeted by
 * {@code auth-begin-rollback-window}.
 *
 * <p>The caller may instead assert that authority is already published
 * ({@link #markAssumedPublished}), which completes the step immediately and touches nothing on-chain.
 *
 * <p><b>Return-value convention</b> (deliberate, and different from {@link KeriAttestService} /
 * {@link KeriCredentialService}): {@link #submitAuthBegin} returns {@link Either#left} <em>only</em>
 * when the initial {@link CeremonyService#beginStep} guard fails, because the ceremony never left its
 * prior state and there is nothing to report through it. Every later failure transitions the ceremony
 * to {@code FAILED} via {@code failStep} and still returns {@link Either#right} with that state: the
 * request was accepted and processed, and its outcome is visible in the returned ceremony.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriAuthBeginService {

    private static final List<Long> AUTH_BEGIN_AUTHORIZED_LABELS = List.of(1447L);

    private final KeriAttestationClient client;
    private final CesrChainReducer cesrChainReducer;
    private final CredentialCesrFetcher cesrFetcher;
    private final ApplicationEventPublisher eventPublisher;
    private final CeremonyService ceremonyService;
    private final KeriIdentityLinkRepository identityLinkRepository;
    private final CredentialChainValidator chainValidator;

    /**
     * Moves the ceremony from {@code CREDENTIAL_RECEIVED} to {@code AUTH_BEGIN_SUBMITTED} and then
     * either records the caller's "already published" assertion or hands the publication to
     * {@code blockchain_publisher}. See the class javadoc for the return-value convention.
     */
    public Either<ProblemDetail, CeremonyView> submitAuthBegin(String ceremonyId, String userId,
            boolean assumePublished, boolean retry) {
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

        if (assumePublished) {
            return markAssumedPublished(ceremonyId, userId, generation, ceremony, link);
        }
        return submitOwn(ceremonyId, userId, generation, ceremony, link);
    }

    // --- "I already published it" (user-asserted, UNVERIFIED) ---

    /**
     * Accepts the caller's assertion that AUTH_BEGIN authority is already published on-chain, WITHOUT
     * supplying a tx hash to verify it. Completes the step to {@code AUTH_BEGIN_CONFIRMED} and records
     * an {@code auth_begin_asserted} flag on the link (no hash, no block) so future ceremonies skip the
     * step. No submitter is touched — nothing is read or written on-chain.
     *
     * <p><b>SECURITY / TODO(policy):</b> there is NO on-chain verification of this claim here. This
     * mirrors the deliberately-relaxed credential policy elsewhere in this module — re-enable a
     * mandatory verification path (or drop this escape hatch) once the authority policy is finalized.
     */
    private Either<ProblemDetail, CeremonyView> markAssumedPublished(String ceremonyId, String userId, int generation,
            KeriAttestationCeremonyEntity ceremony, KeriIdentityLinkEntity link) {
        log.warn("SECURITY: AUTH_BEGIN accepted for user {} without any on-chain verification "
                + "(user asserted 'already published', no tx hash supplied). // TODO(policy): require verification",
                link.getUserId());

        String linkUserId = link.getUserId();
        int bindingVersion = ceremony.getBindingVersion();
        try {
            boolean completed = ceremonyService.completeStep(ceremonyId, generation, CeremonyState.AUTH_BEGIN_SUBMITTED,
                    CeremonyState.AUTH_BEGIN_CONFIRMED,
                    c -> markAuthBeginAssertedIfIdentityStillCurrent(linkUserId, bindingVersion));
            if (!completed) {
                log.warn("Skipping AUTH_BEGIN assumed-published completion for ceremony {}: no longer waiting on "
                        + "AUTH_BEGIN_SUBMITTED.", ceremonyId);
                return ceremonyService.get(ceremonyId, userId);
            }
        } catch (Exception e) {
            log.warn("Failed to complete AUTH_BEGIN assumed-published for ceremony {}: {}", ceremonyId, e.getMessage());
            return failAuthBegin(ceremonyId, userId, generation, KeriAttestationProblems.AUTH_BEGIN_ROLLED_BACK,
                    "Failed to persist the AUTH_BEGIN confirmation: " + e.getMessage());
        }
        log.info("AUTH_BEGIN marked as already-published (user-asserted, unverified), step complete");
        return ceremonyService.get(ceremonyId, userId);
    }

    // --- fresh AUTH_BEGIN publication, handed to blockchain_publisher ---

    /**
     * Validates the linked credential chain, reduces it to the events an AUTH_BEGIN map carries, and
     * emits an {@link AuthBeginPublishCommand}. Nothing here talks to Cardano.
     *
     * <p>The ceremony deliberately stays in {@code AUTH_BEGIN_SUBMITTED} on success: the step is
     * completed by {@code AuthBeginLedgerUpdateHandler} once the publisher reports the transaction
     * dispatched, or failed by {@code CeremonyCleanupJob} if it never does.
     */
    private Either<ProblemDetail, CeremonyView> submitOwn(String ceremonyId, String userId, int generation,
            KeriAttestationCeremonyEntity ceremony, KeriIdentityLinkEntity link) {
        if (link.getCredentialSaid() == null || link.getCredentialSchemaSaid() == null) {
            return failAuthBegin(ceremonyId, userId, generation, KeriAttestationProblems.IDENTITY_NOT_LINKED,
                    "User %s has no validated credential to build an AUTH_BEGIN chain from."
                            .formatted(link.getUserId()));
        }
        if (ceremony.getOrganisationId() == null) {
            return failAuthBegin(ceremonyId, userId, generation,
                    KeriAttestationProblems.AUTH_BEGIN_SUBMISSION_UNAVAILABLE,
                    "Ceremony %s has no organisation, so its AUTH_BEGIN transaction could never be dispatched."
                            .formatted(ceremonyId));
        }

        byte[] reducedChain;
        try {
            Optional<String> cesrOpt = cesrFetcher.fetch(link.getCredentialSaid());
            if (cesrOpt.isEmpty()) {
                return failAuthBegin(ceremonyId, userId, generation, KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED,
                        "Credential %s was not found in the credential store.".formatted(link.getCredentialSaid()));
            }
            String fullCesr = cesrOpt.get();

            // The same gate as credential presentation, applied again immediately before this identity's
            // chain is published on-chain. See CredentialChainValidator for what is (and is not) enforced.
            Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> validated = chainValidator.validate(
                    fullCesr, link.getAid(), link.getCredentialSaid(), link.getCredentialSchemaSaid());
            if (validated.isLeft()) {
                return failAuthBegin(ceremonyId, userId, generation, KeriAttestationProblems.CREDENTIAL_REJECTED,
                        validated.getLeft().getDetail());
            }

            reducedChain = cesrChainReducer.reduceToVcpIssAcdc(fullCesr);
        } catch (Exception e) {
            interruptIfNeeded(e);
            log.warn("Failed to build the AUTH_BEGIN chain for ceremony {}: {}", ceremonyId, e.getMessage());
            return failAuthBegin(ceremonyId, userId, generation, KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED,
                    "Failed to build the AUTH_BEGIN credential chain: " + e.getMessage());
        }

        eventPublisher.publishEvent(new AuthBeginPublishCommand(ceremonyId, ceremony.getOrganisationId(),
                link.getAid(), link.getCredentialSchemaSaid(), reducedChain, AUTH_BEGIN_AUTHORIZED_LABELS));

        log.info("AUTH_BEGIN publication handed to blockchain_publisher for ceremony {}", ceremonyId);

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
     * <p>The re-fetch is row-locked via
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

    /**
     * Records the user-asserted, UNVERIFIED AUTH_BEGIN completion on the identity link: sets the
     * {@code auth_begin_asserted} flag (no tx hash, no block). Same relink-race guard and row-lock idiom
     * as {@link #persistAuthBeginIfIdentityStillCurrent}; only ever called from inside a {@link
     * CeremonyService#completeStep} mutator.
     */
    private void markAuthBeginAssertedIfIdentityStillCurrent(String userId, int expectedBindingVersion) {
        identityLinkRepository.findByUserIdForUpdate(userId).ifPresent(freshLink -> {
            if (freshLink.getBindingVersion() != expectedBindingVersion) {
                log.warn("Skipping AUTH_BEGIN asserted-flag write for user {}: identity was relinked (expected binding "
                        + "version {}, now {}).", userId, expectedBindingVersion, freshLink.getBindingVersion());
                return;
            }
            freshLink.setAuthBeginAsserted(true);
            freshLink.setAuthBeginAt(Instant.now());
            identityLinkRepository.save(freshLink);
        });
    }

    /**
     * Restores the interrupt flag when {@code e} is an interruption in EITHER form.
     *
     * <p>Both kinds have to be named. {@code Thread.sleep} still raises the checked
     * {@link InterruptedException}, but a signify client call now wraps one in
     * {@link SignifyInterruptedException} — which extends {@code RuntimeException}, not
     * {@code InterruptedException}. Testing only the checked type therefore matches nothing the client
     * throws any more: the interrupt is caught by the surrounding {@code catch (Exception e)}, reported
     * as an ordinary step failure, and the flag is silently dropped — so a caller polling or sleeping
     * afterwards keeps going to its full timeout instead of stopping.
     */
    private static void interruptIfNeeded(Exception e) {
        if (e instanceof InterruptedException || e instanceof SignifyInterruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
