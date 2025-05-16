package org.cardanofoundation.lob.app.csv_erp_adapter.service.event_handle;

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
import org.cardanofoundation.lob.app.csv_erp_adapter.service.internal.CsvExtractionService;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@ExtendWith(MockitoExtension.class)
class CsvEventHandlerTest {

    @Mock
    private CsvExtractionService csvExtractionService;

    @InjectMocks
    private CsvEventHandler csvEventHandler;

    @Test
    void handleScheduleIngestionEvent_wrongType() {
        ScheduledIngestionEvent event = mock(ScheduledIngestionEvent.class);

        when(event.getExtractorType()).thenReturn(ExtractorType.NETSUITE);

        csvEventHandler.handleScheduledIngestionEvent(event);

        verifyNoInteractions(csvExtractionService);
    }

    @Test
    void handleScheduleIngestionEvent_correctType() {
        ScheduledIngestionEvent event = mock(ScheduledIngestionEvent.class);
        EventMetadata metadata = mock(EventMetadata.class);
        when(event.getExtractorType()).thenReturn(ExtractorType.CSV);
        when(event.getMetadata()).thenReturn(metadata);
        when(metadata.getUser()).thenReturn("user");

        csvEventHandler.handleScheduledIngestionEvent(event);

        verify(csvExtractionService).startNewExtraction(
                event.getOrganisationId(),
                event.getMetadata().getUser(),
                event.getUserExtractionParameters(),
                event.getFile()
        );
    }

    @Test
    void handleTransactionBatchCreatedEvent_wrongType() {
        TransactionBatchCreatedEvent event = mock(TransactionBatchCreatedEvent.class);
        when(event.getExtractorType()).thenReturn(ExtractorType.NETSUITE);

        csvEventHandler.handleTransactionBatchCreatedEvent(event);

        verifyNoInteractions(csvExtractionService);
    }

    @Test
    void handleTransactionBatchCreatedEvent_correctType() {
        TransactionBatchCreatedEvent event = mock(TransactionBatchCreatedEvent.class);
        when(event.getExtractorType()).thenReturn(ExtractorType.CSV);
        when(event.getBatchId()).thenReturn("batch-id");
        when(event.getOrganisationId()).thenReturn("org-id");

        csvEventHandler.handleTransactionBatchCreatedEvent(event);

        verify(csvExtractionService).continueERPExtraction(
                event.getBatchId(),
                event.getOrganisationId(),
                event.getUserExtractionParameters(),
                event.getSystemExtractionParameters()
        );
    }

    @Test
    void handleScheduledReconcialtionEvent_wrongType() {
        ScheduledReconcilationEvent event = mock(ScheduledReconcilationEvent.class);
        when(event.getExtractorType()).thenReturn(ExtractorType.NETSUITE);

        csvEventHandler.handleScheduledReconciliationEvent(event);

        verifyNoInteractions(csvExtractionService);
    }

    @Test
    void handleScheduledReconciliationEvent_correctType() {
        ScheduledReconcilationEvent event = mock(ScheduledReconcilationEvent.class);
        EventMetadata metadata = mock(EventMetadata.class);
        when(metadata.getUser()).thenReturn("user-id");
        when(event.getExtractorType()).thenReturn(ExtractorType.CSV);
        when(event.getFrom()).thenReturn(LocalDate.EPOCH);
        when(event.getTo()).thenReturn(LocalDate.EPOCH);
        when(event.getFile()).thenReturn(new byte[0]);
        when(event.getOrganisationId()).thenReturn("org-id");
        when(event.getMetadata()).thenReturn(metadata);
        csvEventHandler.handleScheduledReconciliationEvent(event);

        verify(csvExtractionService).startNewReconciliation(
                event.getOrganisationId(),
                event.getMetadata().getUser(),
                event.getFile(),
                event.getFrom(),
                event.getTo()
        );
    }

    @Test
    void handleCreatedReconcilationEvent_wrongType() {
        ReconcilationCreatedEvent event = mock(ReconcilationCreatedEvent.class);
        when(event.getExtractorType()).thenReturn(ExtractorType.NETSUITE);

        csvEventHandler.handleCreatedReconciliationEvent(event);

        verifyNoInteractions(csvExtractionService);
    }

    @Test
    void handleCreatedReconcilationEvent_correctType() {
        ReconcilationCreatedEvent event = mock(ReconcilationCreatedEvent.class);
        when(event.getExtractorType()).thenReturn(ExtractorType.CSV);

        csvEventHandler.handleCreatedReconciliationEvent(event);

        verify(csvExtractionService).continueERPReconciliation(
                event.getReconciliationId(),
                event.getOrganisationId()
        );
    }

}
