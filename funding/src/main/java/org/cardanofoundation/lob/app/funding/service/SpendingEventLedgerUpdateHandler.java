package org.cardanofoundation.lob.app.funding.service;

import java.util.Objects;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
import org.cardanofoundation.lob.app.funding.repository.SpendingEventRepository;

/**
 * Handles blockchain-dispatch status updates coming back from the blockchain publisher and applies the new
 * {@code ledgerDispatchStatus} (and tx hash) to the corresponding published spending events.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SpendingEventLedgerUpdateHandler {

    private final SpendingEventRepository spendingEventRepository;

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
            spendingEventRepository.findById(update.getId()).ifPresentOrElse(
                    spendingEvent -> {
                        spendingEvent.setLedgerDispatchStatus(update.getStatus());
                        update.getBlockchainReceipts().stream()
                                .map(BlockchainReceipt::getHash)
                                .filter(Objects::nonNull)
                                .findFirst()
                                .ifPresent(spendingEvent::setTxHash);
                        spendingEventRepository.save(spendingEvent);
                    },
                    // a tx / event that is unknown to the funding module is ignored
                    () -> log.debug("Ignoring ledger update for unknown spending event: {}", update.getId())
            );
        }

        log.info("Finished processing spending event ledger update for organisation:{}", event.getOrganisationId());
    }

}
