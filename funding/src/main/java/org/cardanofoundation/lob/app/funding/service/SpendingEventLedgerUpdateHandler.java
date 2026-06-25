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
import org.cardanofoundation.lob.app.funding.repository.FundingEventRepository;

@Service
@Slf4j
@RequiredArgsConstructor
public class SpendingEventLedgerUpdateHandler {

    private final FundingEventRepository fundingEventRepository;

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
                    fundingEvent -> {
                        fundingEvent.setLedgerDispatchStatus(update.getStatus());
                        fundingEvent.setLedgerDispatchStatusErrorReason(update.getLedgerDispatchStatusErrorReason());
                        update.getBlockchainReceipts().stream()
                                .map(BlockchainReceipt::getHash)
                                .filter(Objects::nonNull)
                                .findFirst()
                                .ifPresent(fundingEvent::setTxHash);
                        fundingEventRepository.save(fundingEvent);
                    },
                    () -> log.debug("Ignoring ledger update for unknown event: {}", update.getId())
            );
        }

        log.info("Finished processing spending event ledger update for organisation:{}", event.getOrganisationId());
    }

}
