package org.cardanofoundation.lob.app.blockchain_publisher.service.event_handle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TransactionLedgerUpdateCommand;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TransactionStatusRequestEvent;
import org.cardanofoundation.lob.app.blockchain_publisher.service.BlockchainPublisherService;
import org.cardanofoundation.lob.app.reporting.dto.events.PublishReportEvent;

@Service
@Slf4j
@RequiredArgsConstructor
public class BlockchainPublisherEventHandler {

    private final BlockchainPublisherService blockchainPublisherService;

    // received when a ledger update command is published meaning accounting core has changed to the transaction status = MARK_DISPATCH
    @EventListener
    @Async
    public void handleLedgerUpdateCommand(TransactionLedgerUpdateCommand command) {
        log.info("Received LedgerUpdateCommand: {}", command);

        blockchainPublisherService.storeTransactionForDispatchLater(
                command.getOrganisationId(),
                command.getTransactions()
        );
    }

    @EventListener
    @Async
    public void handleReportPublishingEvent(PublishReportEvent event) {
        log.info("Received ReportPublishEvent: {}", event);

        blockchainPublisherService.storeReportsForDispatchLater(event);
    }

    @EventListener
    @Async
    public void handleTransactionStatusRequestEvent(TransactionStatusRequestEvent event) {
        log.info("Received TransactionStatusRequestEvent: {}", event);
        blockchainPublisherService.handleTxStatusRequest(event);
    }

}
