package org.cardanofoundation.lob.app.blockchain_publisher.service.event_handle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TransactionLedgerUpdateCommand;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TransactionStatusRequestEvent;
import org.cardanofoundation.lob.app.blockchain_common.domain.events.AuthBeginPublishCommand;
import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.blockchain_publisher.service.BlockchainPublisherService;
import org.cardanofoundation.lob.app.funding.domain.events.SpendingEventsPublishCommand;
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

    @EventListener
    @Async
    public void handleEventsPublishCommand(SpendingEventsPublishCommand spendingEventsPublishCommand) {
        log.info("Received SpendingEventsPublishCommand: {}", spendingEventsPublishCommand);

        blockchainPublisherService.storeEventsForDispatchLater(spendingEventsPublishCommand);
    }

    // AFTER_COMMIT: storing the publisher row is a step toward an irreversible on-chain action, so it
    // must not act on a vault transaction that could still roll back and leave an orphan row here.
    //
    // fallbackExecution is required, not incidental: DocumentDispatchRetryJob re-emits this command
    // outside any transaction synchronization, and AFTER_COMMIT listeners are silently skipped when
    // none is active.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async
    public void handleDocumentPublishCommand(DocumentPublishCommand command) {
        // Log ids only — the command carries ciphertext.
        log.info("Received DocumentPublishCommand for organisation:{}, document:{}",
                command.organisationId(), command.documentId());
        blockchainPublisherService.storeDocumentForDispatchLater(command);
    }

    // Same AFTER_COMMIT + fallbackExecution reasoning as the document handler above: queuing an
    // AUTH_BEGIN row leads to an irreversible on-chain publication, so it must not act on a
    // keri_attestation transaction that could still roll back.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async
    public void handleAuthBeginPublishCommand(AuthBeginPublishCommand command) {
        log.info("Received AuthBeginPublishCommand for organisation:{}, ceremony:{}",
                command.organisationId(), command.ceremonyId());
        blockchainPublisherService.storeAuthBeginForDispatchLater(command);
    }

}
