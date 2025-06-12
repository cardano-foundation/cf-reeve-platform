package org.cardanofoundation.lob.app.csv_erp_adapter.service.event_handle;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ScheduledIngestionEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ValidateIngestionEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ScheduledReconcilationEvent;

public interface ReeveErpAdapter {

    void handleScheduledIngestionEvent(ScheduledIngestionEvent event);
    void handleTransactionBatchCreatedEvent(TransactionBatchCreatedEvent transactionBatchCreatedEvent);
    void handleScheduledReconciliationEvent(ScheduledReconcilationEvent scheduledReconcilationEvent);
    void handleCreatedReconciliationEvent(ReconcilationCreatedEvent reconcilationCreatedEvent);
    void handleValidateIngestionEvent(ValidateIngestionEvent event);

}
