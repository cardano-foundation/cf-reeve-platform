package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.event_handle;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ScheduledIngestionEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ScheduledReconcilationEvent;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.NetSuiteExtractionService;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.NetSuiteReconcilationService;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@ExtendWith(MockitoExtension.class)
class NetSuiteEventHandlerTest {

    @Mock
    private NetSuiteExtractionService netSuiteExtractionService;
    @Mock
    private NetSuiteReconcilationService netSuiteReconcilationService;

    @InjectMocks
    private NetSuiteEventHandler netSuiteEventHandler;

    @Test
    void handleScheduleIngestionEvent_wrongType() {
        ScheduledIngestionEvent event = mock(ScheduledIngestionEvent.class);

        when(event.getExtractorType()).thenReturn(ExtractorType.CSV);

        netSuiteEventHandler.handleScheduledIngestionEvent(event);

        verifyNoInteractions(netSuiteExtractionService);
    }

    @Test
    void handleScheduleIngestionEvent_correctType() {
        ScheduledIngestionEvent event = mock(ScheduledIngestionEvent.class);
        EventMetadata metadata = mock(EventMetadata.class);
        when(event.getExtractorType()).thenReturn(ExtractorType.NETSUITE);
        when(event.getMetadata()).thenReturn(metadata);
        when(metadata.getUser()).thenReturn("user");

        netSuiteEventHandler.handleScheduledIngestionEvent(event);

        verify(netSuiteExtractionService).startNewERPExtraction(
                event.getOrganisationId(),
                event.getMetadata().getUser(),
                event.getUserExtractionParameters()
        );
    }

    @Test
    void handleTransactionBatchCreatedEvent_wrongType() {
        TransactionBatchCreatedEvent event = mock(TransactionBatchCreatedEvent.class);
        when(event.getExtractorType()).thenReturn(ExtractorType.CSV);

        netSuiteEventHandler.handleTransactionBatchCreatedEvent(event);

        verifyNoInteractions(netSuiteExtractionService);
    }

    @Test
    void handleTransactionBatchCreatedEvent_correctType() {
        TransactionBatchCreatedEvent event = mock(TransactionBatchCreatedEvent.class);
        when(event.getExtractorType()).thenReturn(ExtractorType.NETSUITE);
        when(event.getBatchId()).thenReturn("batch-id");
        when(event.getOrganisationId()).thenReturn("org-id");

        netSuiteEventHandler.handleTransactionBatchCreatedEvent(event);

        verify(netSuiteExtractionService).continueERPExtraction(
                event.getBatchId(),
                event.getOrganisationId(),
                event.getUserExtractionParameters(),
                event.getSystemExtractionParameters()
        );
    }

    @Test
    void handleScheduledReconcialtionEvent_wrongType() {
        ScheduledReconcilationEvent event = mock(ScheduledReconcilationEvent.class);
        when(event.getExtractorType()).thenReturn(ExtractorType.CSV);

        netSuiteEventHandler.handleScheduledReconciliationEvent(event);

        verifyNoInteractions(netSuiteExtractionService);
    }

    @Test
    void handleScheduledReconciliationEvent_correctType() {
        ScheduledReconcilationEvent event = mock(ScheduledReconcilationEvent.class);
        EventMetadata metadata = mock(EventMetadata.class);
        when(metadata.getUser()).thenReturn("user-id");
        when(event.getExtractorType()).thenReturn(ExtractorType.NETSUITE);
        when(event.getFrom()).thenReturn(LocalDate.EPOCH);
        when(event.getTo()).thenReturn(LocalDate.EPOCH);
        when(event.getOrganisationId()).thenReturn("org-id");
        when(event.getMetadata()).thenReturn(metadata);
        netSuiteEventHandler.handleScheduledReconciliationEvent(event);

        verify(netSuiteReconcilationService).startERPReconcilation(
                event.getOrganisationId(),
                event.getMetadata().getUser(),
                event.getFrom(),
                event.getTo()
        );
    }

    @Test
    void handleCreatedReconcilationEvent_wrongType() {
        ReconcilationCreatedEvent event = mock(ReconcilationCreatedEvent.class);
        when(event.getExtractorType()).thenReturn(ExtractorType.CSV);

        netSuiteEventHandler.handleCreatedReconciliationEvent(event);

        verifyNoInteractions(netSuiteExtractionService);
    }

    @Test
    void handleCreatedReconcilationEvent_correctType() {
        ReconcilationCreatedEvent event = mock(ReconcilationCreatedEvent.class);
        when(event.getExtractorType()).thenReturn(ExtractorType.NETSUITE);

        netSuiteEventHandler.handleCreatedReconciliationEvent(event);

        verify(netSuiteReconcilationService).continueReconcilation(
                event.getReconciliationId(),
                event.getOrganisationId(),
                event.getFrom(),
                event.getTo()
        );
    }

}
