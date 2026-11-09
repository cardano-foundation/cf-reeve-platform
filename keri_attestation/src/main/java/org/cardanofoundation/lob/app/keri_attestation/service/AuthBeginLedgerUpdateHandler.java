package org.cardanofoundation.lob.app.keri_attestation.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;

/**
 * Closes the AUTH_BEGIN loop: {@code blockchain_publisher} reports the transaction it published for a
 * ceremony, and this advances that ceremony from {@code AUTH_BEGIN_SUBMITTED} to
 * {@code AUTH_BEGIN_CONFIRMED}, recording the tx hash on the identity link so later ceremonies skip
 * the step.
 *
 * <p>The ledger update's id is the ceremony id — that is the correlation handle across the process
 * boundary, since the two modules may run in different pods.
 *
 * <p>Same listener shape as {@code document_vault}'s ledger handler and for the same reasons:
 * AFTER_COMMIT so a rolled-back publisher transaction never advances a ceremony,
 * {@code fallbackExecution} because some emitters publish with no active transaction
 * synchronization, and REQUIRES_NEW because this runs on the async executor detached from any
 * caller's transaction.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuthBeginLedgerUpdateHandler {

    /** Statuses that mean the transaction reached the chain; any of them completes the step. */
    private static final Set<LedgerDispatchStatus> ON_CHAIN_STATUSES = EnumSet.of(
            LedgerDispatchStatus.DISPATCHED, LedgerDispatchStatus.COMPLETED, LedgerDispatchStatus.FINALIZED);

    private final KeriAttestationCeremonyRepository ceremonyRepository;
    private final KeriIdentityLinkRepository identityLinkRepository;
    private final CeremonyService ceremonyService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleLedgerUpdatedEvent(LedgerUpdatedEvent event) {
        if (event.getType() != LedgerUpdateType.AUTH_BEGIN) {
            return;
        }
        log.info("Received AUTH_BEGIN ledger update for organisation:{}, updates:{}",
                event.getOrganisationId(), event.getStatusUpdates().size());

        for (LedgerStatusUpdate update : event.getStatusUpdates()) {
            apply(update);
        }
    }

    private void apply(LedgerStatusUpdate update) {
        String ceremonyId = update.getId();

        if (update.getStatus() == LedgerDispatchStatus.FAILED) {
            ceremonyRepository.findById(ceremonyId).ifPresent(ceremony ->
                    ceremonyService.failStep(ceremonyId, ceremony.getAttemptGeneration(),
                            CeremonyState.AUTH_BEGIN_SUBMITTED,
                            KeriAttestationProblems.AUTH_BEGIN_ROLLED_BACK,
                            "The AUTH_BEGIN transaction could not be published: %s"
                                    .formatted(update.getLedgerDispatchStatusErrorReason())));

            return;
        }

        // Anything short of an on-chain dispatch leaves the ceremony waiting: the cleanup job's
        // stale-step sweep is what eventually resolves one whose transaction never lands. The step
        // completes at DISPATCHED rather than FINALIZED — AUTH_BEGIN never waited on confirmation
        // depth, and finality arrives hours later.
        if (!ON_CHAIN_STATUSES.contains(update.getStatus())) {
            return;
        }

        String txHash = update.getBlockchainReceipts().stream()
                .map(BlockchainReceipt::getHash)
                .filter(hash -> hash != null)
                .findFirst()
                .orElse(null);

        ceremonyRepository.findById(ceremonyId).ifPresentOrElse(
                ceremony -> complete(ceremony, txHash),
                () -> log.debug("Ignoring AUTH_BEGIN ledger update for unknown ceremony: {}", ceremonyId));
    }

    private void complete(KeriAttestationCeremonyEntity ceremony, String txHash) {
        String userId = ceremony.getUserId();
        int bindingVersion = ceremony.getBindingVersion();

        boolean completed = ceremonyService.completeStep(ceremony.getId(), ceremony.getAttemptGeneration(),
                CeremonyState.AUTH_BEGIN_SUBMITTED, CeremonyState.AUTH_BEGIN_CONFIRMED,
                c -> {
                    c.setAuthBeginTxHash(txHash);
                    persistAuthBeginIfIdentityStillCurrent(userId, bindingVersion, txHash);
                });

        if (completed) {
            log.info("AUTH_BEGIN confirmed for ceremony {} via tx {}", ceremony.getId(), txHash);
        } else {
            log.warn("Skipping AUTH_BEGIN completion for ceremony {}: no longer waiting on AUTH_BEGIN_SUBMITTED.",
                    ceremony.getId());
        }
    }

    /**
     * Writes the published tx hash onto the identity link, guarded against a relink that happened while
     * the transaction was in flight — the same row-locked binding-version check
     * {@code KeriCredentialService} uses for the identical race. Only ever called from inside a
     * {@link CeremonyService#completeStep} mutator, so it commits with the ceremony transition.
     */
    private void persistAuthBeginIfIdentityStillCurrent(String userId, int expectedBindingVersion, String txHash) {
        identityLinkRepository.findByUserIdForUpdate(userId).ifPresent(freshLink -> {
            if (freshLink.getBindingVersion() != expectedBindingVersion) {
                log.warn("Skipping AUTH_BEGIN link write for user {}: identity was relinked (expected binding "
                        + "version {}, now {}).", userId, expectedBindingVersion, freshLink.getBindingVersion());

                return;
            }
            freshLink.setAuthBeginTxHash(txHash);
            freshLink.setAuthBeginAt(Instant.now());
            identityLinkRepository.save(freshLink);
        });
    }

}
