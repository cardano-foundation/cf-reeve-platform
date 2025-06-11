package org.cardanofoundation.lob.app.blockchain_publisher.service.event_handle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.ReportLedgerUpdateCommand;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TransactionLedgerUpdateCommand;
import org.cardanofoundation.lob.app.blockchain_publisher.service.BlockchainPublisherService;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.blockchain_publisher.enabled", havingValue = "true", matchIfMissing = true)
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
    public void handleLedgerUpdateCommand(ReportLedgerUpdateCommand command) {
        log.info("Received ReportLedgerUpdateCommand: {}", command);

        blockchainPublisherService.storeReportsForDispatchLater(
                command.getOrganisationId(),
                command.getReports()
        );
    }

}
