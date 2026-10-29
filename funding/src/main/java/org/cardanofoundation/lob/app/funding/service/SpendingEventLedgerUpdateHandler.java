package org.cardanofoundation.lob.app.funding.service;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.repository.FundingEventRepository;

@Service
@Slf4j
@RequiredArgsConstructor
public class SpendingEventLedgerUpdateHandler {

    /** Statuses meaning the event's transaction reached the chain. */
    private static final Set<LedgerDispatchStatus> ON_CHAIN = Set.of(
            LedgerDispatchStatus.DISPATCHED, LedgerDispatchStatus.COMPLETED, LedgerDispatchStatus.FINALIZED);

    private final FundingEventRepository fundingEventRepository;
    private final SpendingEventOnChainLookup onChainLookup;

    @EventListener
    @Async
    @Transactional
    public void handleLedgerUpdatedEvent(LedgerUpdatedEvent event) {
        if (event.getType() != LedgerUpdateType.SPENDING_EVENT) {
            return;
        }

        log.info("Received spending event ledger update for organisation:{}, updates:{}",
                event.getOrganisationId(), event.getStatusUpdates().size());

        for (LedgerStatusUpdate update : event.getStatusUpdates()) {
            fundingEventRepository.findById(update.getId()).ifPresentOrElse(
                    fundingEvent -> apply(fundingEvent, update),
                    () -> log.debug("Ignoring ledger update for unknown event: {}", update.getId())
            );
        }

        log.info("Finished processing spending event ledger update for organisation:{}", event.getOrganisationId());
    }

    private void apply(FundingEventEntity fundingEvent, LedgerStatusUpdate update) {
        // only a published event is on its way to the chain; an update for anything else is late or out of order
        // (e.g. it arrives after the event was reverted to draft and edited) and must not overwrite it
        if (fundingEvent.getStatus() != EventStatus.PUBLISHED) {
            log.info("Ignoring ledger update {} for event {} in status {}", update.getStatus(), fundingEvent.getId(), fundingEvent.getStatus());
            return;
        }

        if (update.getStatus() == LedgerDispatchStatus.FAILED) {
            onChainLookup.findOnChainTxHash(fundingEvent.getOrganisationId(), fundingEvent.getId()).ifPresentOrElse(
                    txHash -> keepAsPublished(fundingEvent, txHash),
                    () -> revertToDraft(fundingEvent, update));
        } else {
            fundingEvent.setLedgerDispatchStatus(update.getStatus());
            fundingEvent.setLedgerDispatchStatusErrorReason(update.getLedgerDispatchStatusErrorReason());
            receiptHash(update).ifPresent(txHash -> {
                fundingEvent.setTxHash(txHash);
                if (ON_CHAIN.contains(update.getStatus())) {
                    clearLastFailure(fundingEvent);
                }
            });
        }
        fundingEventRepository.save(fundingEvent);
    }

    /**
     * The publisher gave up on the event (LOB-2380): put it back to draft so the user can correct it, or simply
     * publish it again. Its project/milestone structure is no longer locked by it, since locks only apply to
     * published events.
     */
    private static void revertToDraft(FundingEventEntity fundingEvent, LedgerStatusUpdate update) {
        log.warn("Publishing of event {} failed, reverting it to draft, reason: {}", fundingEvent.getId(), update.getLedgerDispatchStatusErrorReason());

        fundingEvent.setStatus(EventStatus.DRAFT);
        fundingEvent.setLedgerDispatchApproved(false);
        fundingEvent.setLedgerDispatchStatus(LedgerDispatchStatus.FAILED);
        fundingEvent.setLedgerDispatchStatusErrorReason(update.getLedgerDispatchStatusErrorReason());
        fundingEvent.setLastFailureMessage(update.getLedgerDispatchStatusErrorReason());
        fundingEvent.setLastFailureAt(LocalDateTime.now());
        fundingEvent.setTxHash(null);
        fundingEvent.setPublishedAt(null);
    }

    /** The publisher reported a failure, but the event is on-chain after all: it stays published. */
    private static void keepAsPublished(FundingEventEntity fundingEvent, String txHash) {
        log.warn("Publishing of event {} was reported as failed, but it is on-chain in tx {}; keeping it published", fundingEvent.getId(), txHash);

        fundingEvent.setLedgerDispatchStatus(LedgerDispatchStatus.DISPATCHED);
        fundingEvent.setLedgerDispatchStatusErrorReason(null);
        fundingEvent.setTxHash(txHash);
    }

    private static void clearLastFailure(FundingEventEntity fundingEvent) {
        fundingEvent.setLastFailureMessage(null);
        fundingEvent.setLastFailureAt(null);
    }

    private static Optional<String> receiptHash(LedgerStatusUpdate update) {
        return update.getBlockchainReceipts().stream()
                .map(BlockchainReceipt::getHash)
                .filter(Objects::nonNull)
                .findFirst();
    }

}
