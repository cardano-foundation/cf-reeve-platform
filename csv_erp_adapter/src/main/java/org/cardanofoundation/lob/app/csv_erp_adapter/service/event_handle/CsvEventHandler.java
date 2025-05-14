package org.cardanofoundation.lob.app.csv_erp_adapter.service.event_handle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ScheduledIngestionEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ScheduledReconcilationEvent;
import org.cardanofoundation.lob.app.csv_erp_adapter.service.internal.CsvExtractionService;

@Slf4j
@RequiredArgsConstructor
@Service
@ConditionalOnProperty(value = "lob.csv.enabled", havingValue = "true", matchIfMissing = true)
public class CsvEventHandler implements ReeveErpAdapter {

    private final CsvExtractionService csvExtractionService;

    @Override
    @Async
    @EventListener
    public void handleScheduledIngestionEvent(ScheduledIngestionEvent event) {
        log.info("Handling handleScheduledIngestionEvent...");
        if (event.getExtractorType() != ExtractorType.CSV) {
            log.info("Ignoring event for extractor type: {}", event.getExtractorType());
            return;
        }
        csvExtractionService.startNewExtraction(
                event.getOrganisationId(),
                event.getMetadata().getUser(),
                event.getUserExtractionParameters(),
                event.getFile()
        );
        log.info("Handled handleScheduledIngestionEvent.");
    }

    @Override
    @Async
    @EventListener
    public void handleTransactionBatchCreatedEvent(TransactionBatchCreatedEvent transactionBatchCreatedEvent) {
        if (transactionBatchCreatedEvent.getExtractorType() != ExtractorType.CSV) {
            log.info("Ignoring event for extractor type: {}", transactionBatchCreatedEvent.getExtractorType());
            return;
        }
        log.info("Handling handleTransactionBatchCreatedEvent...");
        csvExtractionService.continueERPExtraction(
                transactionBatchCreatedEvent.getBatchId(),
                transactionBatchCreatedEvent.getOrganisationId(),
                transactionBatchCreatedEvent.getUserExtractionParameters(),
                transactionBatchCreatedEvent.getSystemExtractionParameters()
        );
        log.info("Handled handleTransactionBatchCreatedEvent.");
    }

    @Override
    @Async
    @EventListener
    public void handleScheduledReconciliationEvent(ScheduledReconcilationEvent scheduledReconcilationEvent) {
        log.info("Handling handleScheduledReconciliationEvent...");
        if (scheduledReconcilationEvent.getExtractorType() != ExtractorType.CSV) {
            log.info("Ignoring event for extractor type: {}", scheduledReconcilationEvent.getExtractorType());
            return;
        }
        csvExtractionService.startNewReconciliation(
                scheduledReconcilationEvent.getOrganisationId(),
                scheduledReconcilationEvent.getMetadata().getUser(),
                scheduledReconcilationEvent.getFile(),
                scheduledReconcilationEvent.getFrom(),
                scheduledReconcilationEvent.getTo()
        );
        log.info("Handled handleScheduledReconciliationEvent.");
    }

    @Override
    @Async
    @EventListener
    public void handleCreatedReconciliationEvent(ReconcilationCreatedEvent reconcilationCreatedEvent) {
        log.info("Handling handleCreatedReconciliationEvent...");
        if (reconcilationCreatedEvent.getExtractorType() != ExtractorType.CSV) {
            log.info("Ignoring event for extractor type: {}", reconcilationCreatedEvent.getExtractorType());
            return;
        }
        csvExtractionService.continueERPReconciliation(
                reconcilationCreatedEvent.getReconciliationId(),
                reconcilationCreatedEvent.getOrganisationId(),
                reconcilationCreatedEvent.getFrom(),
                reconcilationCreatedEvent.getTo()
        );
        log.info("Handled handleCreatedReconciliationEvent.");
    }
}
