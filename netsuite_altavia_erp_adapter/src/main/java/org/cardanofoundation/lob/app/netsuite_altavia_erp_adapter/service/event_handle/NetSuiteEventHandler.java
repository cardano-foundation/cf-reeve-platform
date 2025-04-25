package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.event_handle;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ScheduledIngestionEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ScheduledReconcilationEvent;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.NetSuiteExtractionService;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.NetSuiteReconcilationService;

@Slf4j
@RequiredArgsConstructor
//@Service  // Service not needed since it will be defined in the config of the implementing application
@ConditionalOnProperty(value = "lob.netsuite.enabled", havingValue = "true", matchIfMissing = true)
public class NetSuiteEventHandler {

    private final NetSuiteExtractionService netSuiteExtractionService;
    private final NetSuiteReconcilationService netSuiteReconcilationService;

    @EventListener
    @Async
    public void handleScheduledIngestionEvent(ScheduledIngestionEvent event) {
        log.info("Handling handleScheduledIngestionEvent...");

        netSuiteExtractionService.startNewERPExtraction(
                event.getOrganisationId(),
                event.getMetadata().getUser(),
                event.getUserExtractionParameters()
        );

        log.info("Handled handleScheduledIngestionEvent.");
    }

    @EventListener
    @Async
    public void handleTransactionBatchCreatedEvent(TransactionBatchCreatedEvent transactionBatchCreatedEvent) {
        log.info("Handling handleTransactionBatchCreatedEvent...");

        netSuiteExtractionService.continueERPExtraction(
                transactionBatchCreatedEvent.getBatchId(),
                transactionBatchCreatedEvent.getOrganisationId(),
                transactionBatchCreatedEvent.getUserExtractionParameters(),
                transactionBatchCreatedEvent.getSystemExtractionParameters()
        );

        log.info("Handled handleTransactionBatchCreatedEvent.");
    }

    @EventListener
    @Async
    public void handleScheduledReconciliationEvent(ScheduledReconcilationEvent scheduledReconcilationEvent) {
        log.info("Handling handleScheduledReconciliationEvent...");

        netSuiteReconcilationService.startERPReconcilation(
                scheduledReconcilationEvent.getOrganisationId(),
                scheduledReconcilationEvent.getMetadata().getUser(),
                scheduledReconcilationEvent.getFrom(),
                scheduledReconcilationEvent.getTo()
        );

        log.info("Handled handleScheduledReconciliationEvent.");
    }

    @EventListener
    @Async
    public void handleCreatedReconciliationEvent(ReconcilationCreatedEvent reconcilationCreatedEvent) {
        log.info("Handling handleCreatedReconciliationEvent...");

        netSuiteReconcilationService.continueReconcilation(
                reconcilationCreatedEvent.getReconciliationId(),
                reconcilationCreatedEvent.getOrganisationId(),
                reconcilationCreatedEvent.getFrom(),
                reconcilationCreatedEvent.getTo()
        );

        log.info("Handled handleCreatedReconciliationEvent.");
    }

}
