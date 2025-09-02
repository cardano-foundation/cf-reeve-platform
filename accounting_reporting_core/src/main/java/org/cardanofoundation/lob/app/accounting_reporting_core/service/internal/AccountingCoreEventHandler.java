package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ReportStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchChunkEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchStartedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ValidateIngestionResponseEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.ReportsLedgerUpdatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TxsLedgerUpdatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationChunkEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationFinalisationEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationStartedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.job.TxStatusUpdaterJob;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.ValidateIngestionResponseWaiter;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.ProcessorFlags;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.accounting_reporting_core.enabled", havingValue = "true", matchIfMissing = true)
public class AccountingCoreEventHandler {

    private final ERPIncomingDataProcessor erpIncomingDataProcessor;
    private final TransactionConverter transactionConverter;
    private final LedgerService ledgerService;
    private final TransactionBatchService transactionBatchService;
    private final TransactionReconcilationService transactionReconcilationService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TxStatusUpdaterJob txStatusUpdaterJob;
    private final ValidateIngestionResponseWaiter validateIngestionResponseWaiter;


    @EventListener
    @Async
    public void handleLedgerUpdatedEvent(TxsLedgerUpdatedEvent event) {
        log.info("Received handleLedgerUpdatedEvent event, event: {}", event.getStatusUpdates());

        txStatusUpdaterJob.addToStatusUpdateMap(event.statusUpdatesMap());

        log.info("Finished processing handleLedgerUpdatedEvent event, event: {}", event.getStatusUpdates());
    }

    @EventListener
    @Async
    public void handleValidateIngestionResponseEvent(ValidateIngestionResponseEvent event) {
        log.info("Received handleValidateIngestionResponseEvent, event: {}", event);

        String correlationId = event.getCorrelationId();
        validateIngestionResponseWaiter.complete(correlationId, event);
    }

    @EventListener
    @Async
    public void handleReportsLedgerUpdated(ReportsLedgerUpdatedEvent event) {
        log.info("Received handleReportsLedgerUpdated, event: {}", event);

        Map<String, ReportStatusUpdate> reportStatusUpdatesMap = event.statusUpdatesMap();

        List<ReportEntity> reportEntities = ledgerService.updateReportsWithNewStatuses(reportStatusUpdatesMap);
        ledgerService.saveAllReports(reportEntities);

        log.info("Finished processing handleReportsLedgerUpdated, event: {}", event);
    }

    @EventListener
    @Async
    public void handleTransactionBatchFailedEvent(TransactionBatchFailedEvent event) {
        log.info("Received handleTransactionBatchFailedEvent event, event: {}", event);

        FatalError error = event.getError();

        transactionBatchService.failTransactionBatch(
                event.getBatchId(),
                event.getUserExtractionParameters(),
                event.getSystemExtractionParameters(),
                event.getExtractorType(),
                error
        );

        log.info("Finished processing handleTransactionBatchFailedEvent event, event: {}", event);
    }

    @EventListener
    @Async
    public void handleTransactionBatchStartedEvent(TransactionBatchStartedEvent event) {
        log.info("Received handleTransactionBatchStartedEvent event, event: {}", event);

        erpIncomingDataProcessor.initiateIngestion(
                event.getBatchId(),
                event.getOrganisationId(),
                event.getUserExtractionParameters(),
                event.getSystemExtractionParameters(),
                event.getMetadata().getUser(),
                event.getExtractorType()
        );

        log.info("Finished processing handleTransactionBatchStartedEvent event, event: {}", event);
    }

    @EventListener // we need a sync process to avoid out of order events
    @Async
    public void handleTransactionBatchChunkEvent(TransactionBatchChunkEvent transactionBatchChunkEvent) {
        String batchId = transactionBatchChunkEvent.getBatchId();

        log.info("Received handleTransactionBatchChunkEvent event...., event, batch_id: {}, chunk_size:{}", batchId, transactionBatchChunkEvent.getTransactions().size());

        Set<Transaction> txs = transactionBatchChunkEvent.getTransactions();
        Optional<TransactionBatchEntity> batch = transactionBatchService.findById(batchId);
        Set<TransactionEntity> detachedDbTxs = transactionConverter.convertToDbDetached(txs, batch);

        erpIncomingDataProcessor.continueIngestion(
                transactionBatchChunkEvent.getOrganisationId(),
                batchId,
                transactionBatchChunkEvent.getTotalTransactionsCount(),
                detachedDbTxs,
                new ProcessorFlags(ProcessorFlags.Trigger.IMPORT)
        );

        log.info("Finished processing handleTransactionBatchChunkEvent event...., event, batch_id: {}", batchId);
    }

    @EventListener
    @Async
    public void handleReconcilationChunkFailedEvent(ReconcilationFailedEvent event) {
        log.info("Received handleReconcilationChunkFailedEvent event, event: {}", event);

        transactionReconcilationService.failReconcilation(
                event.getReconciliationId(),
                event.getOrganisationId(),
                Optional.empty(),
                Optional.empty(),
                event.getError()
        );

        log.info("Finished processing handleReconcilationChunkFailedEvent event, event: {}", event);
    }

    @EventListener
    @Async
    public void handleReconcilationStartedEvent(ReconcilationStartedEvent event) {
        log.info("Received handleReconcilationStartedEvent, event: {}", event);

        erpIncomingDataProcessor.initiateReconcilation(event);

        log.info("Finished processing handleReconcilationStartedEvent, event: {}", event);
    }

    @EventListener // we need a sync process to avoid out of order events
    @Async
    public void handleReconcilationChunkEvent(ReconcilationChunkEvent event) {
        log.info("Received handleReconcilationChunkEvent, event: {}", event);

        String reconcilationId = event.getReconciliationId();
        String organisationId = event.getOrganisationId();
        LocalDate fromDate = event.getFrom();
        LocalDate toDate = event.getTo();
        Set<Transaction> transactions = event.getTransactions();
        Set<TransactionEntity> chunkDetachedTxEntities = transactionConverter.convertToDbDetached(transactions, Optional.empty());

        erpIncomingDataProcessor.continueReconcilation(
                reconcilationId,
                organisationId,
                fromDate,
                toDate,
                chunkDetachedTxEntities
        );

        log.info("Finished processing handleReconcilationChunkEvent, event: {}", event);

        applicationEventPublisher.publishEvent(ReconcilationFinalisationEvent.builder()
                .metadata(EventMetadata.create(ReconcilationFinalisationEvent.VERSION))
                .totalPrediction(event.getTotalTransactionsCount())
                .reconciliationId(reconcilationId)
                .organisationId(organisationId)
                .build());

    }

    @EventListener
    @Async
    public void handleReconcilationFinalisation(ReconcilationFinalisationEvent event) {
        log.info("Received handleReconcilationFinalisation, event: {}", event);

        erpIncomingDataProcessor.finialiseReconcilation(event);

        log.info("Finished processing handleReconcilationFinalisation, event: {}", event);
    }

}
