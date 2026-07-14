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
import org.cardanofoundation.lob.app.blockchain_publisher.service.BlockchainPublisherService;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;
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

    // Codex adversarial-review finding 1 (durable publish handoff):
    // - AFTER_COMMIT, not the default BEFORE_COMMIT-ish immediate dispatch of @EventListener: storing
    //   the publisher row is a step toward an irreversible on-chain action, so it must never act on a
    //   vault-side DocumentPublishCommand whose transaction could still roll back. Without this, the
    //   async listener could store the publisher row before the vault TX commits, and a vault rollback
    //   would then leave an orphan publisher row for a document the vault never actually published.
    // - fallbackExecution = true is REQUIRED (not incidental): DocumentDispatchRetryJob re-emits this
    //   same command for documents stuck in PUBLISHED/MARK_DISPATCH, and it may do so outside of any
    //   active transaction synchronization (e.g. after its own read-only transaction has already
    //   committed). AFTER_COMMIT listeners are silently skipped with no synchronization present unless
    //   fallbackExecution is set — without it, the retry job's re-emissions would be dropped on the floor.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async
    public void handleDocumentPublishCommand(DocumentPublishCommand command) {
        // do NOT log the command — it carries ciphertext; log ids only
        log.info("Received DocumentPublishCommand for organisation:{}, document:{}",
                command.organisationId(), command.documentId());
        blockchainPublisherService.storeDocumentForDispatchLater(command);
    }

}
