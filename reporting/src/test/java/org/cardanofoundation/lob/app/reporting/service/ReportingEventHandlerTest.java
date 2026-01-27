package org.cardanofoundation.lob.app.reporting.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;

import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.reporting.dto.events.ReportsLedgerUpdatedEvent;
import org.cardanofoundation.lob.app.reporting.model.ReportStatusUpdate;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@ExtendWith(MockitoExtension.class)
class ReportingEventHandlerTest {

    @Mock
    private ReportingRepository reportingRepository;

    @InjectMocks
    private ReportingEventHandler reportingEventHandler;

    @Test
    void handleReportsLedgerUpdated_withValidEvent_shouldUpdateReport() {
        // Given
        String reportId = "report123";
        String organisationId = "org123";

        ReportEntity reportEntity = ReportEntity.builder()
                .id(reportId)
                .organisationId(organisationId)
                .build();

        BlockchainReceipt receipt = new BlockchainReceipt("CARDANO", "hash123");

        ReportStatusUpdate statusUpdate = new ReportStatusUpdate(
                reportId,
                LedgerDispatchStatus.DISPATCHED,
                null,
                Set.of(receipt)
        );

        ReportsLedgerUpdatedEvent event = ReportsLedgerUpdatedEvent.builder()
                .metadata(EventMetadata.create("test"))
                .organisationId(organisationId)
                .statusUpdates(Set.of(statusUpdate))
                .build();

        when(reportingRepository.findAllById(Set.of(reportId))).thenReturn(List.of(reportEntity));

        // When
        reportingEventHandler.handleReportsLedgerUpdated(event);

        // Then
        ArgumentCaptor<ReportEntity> captor = ArgumentCaptor.forClass(ReportEntity.class);
        verify(reportingRepository).findAllById(Set.of(reportId));
        verify(reportingRepository).save(captor.capture());

        ReportEntity savedReport = captor.getValue();
        assertEquals(LedgerDispatchStatus.DISPATCHED, savedReport.getLedgerDispatchStatus());
        assertNull(savedReport.getLedgerDispatchStatusErrorReason());
        assertEquals("hash123", savedReport.getBlockchainHash());
        assertEquals("CARDANO", savedReport.getBlockchainType());
    }

    @Test
    void handleReportsLedgerUpdated_withErrorReason_shouldSetErrorReason() {
        // Given
        String reportId = "report456";
        String organisationId = "org456";
        String errorReason = "Transaction failed";

        ReportEntity reportEntity = ReportEntity.builder()
                .id(reportId)
                .organisationId(organisationId)
                .build();

        ReportStatusUpdate statusUpdate = new ReportStatusUpdate(
                reportId,
                LedgerDispatchStatus.FAILED,
                errorReason,
                Set.of()
        );

        ReportsLedgerUpdatedEvent event = ReportsLedgerUpdatedEvent.builder()
                .metadata(EventMetadata.create("test"))
                .organisationId(organisationId)
                .statusUpdates(Set.of(statusUpdate))
                .build();

        when(reportingRepository.findAllById(Set.of(reportId))).thenReturn(List.of(reportEntity));

        // When
        reportingEventHandler.handleReportsLedgerUpdated(event);

        // Then
        ArgumentCaptor<ReportEntity> captor = ArgumentCaptor.forClass(ReportEntity.class);
        verify(reportingRepository).save(captor.capture());

        ReportEntity savedReport = captor.getValue();
        assertEquals(LedgerDispatchStatus.FAILED, savedReport.getLedgerDispatchStatus());
        assertEquals(errorReason, savedReport.getLedgerDispatchStatusErrorReason());
        assertNull(savedReport.getBlockchainHash());
        assertNull(savedReport.getBlockchainType());
    }

    @Test
    void handleReportsLedgerUpdated_withEmptyBlockchainReceipts_shouldNotSetBlockchainInfo() {
        // Given
        String reportId = "report789";
        String organisationId = "org789";

        ReportEntity reportEntity = ReportEntity.builder()
                .id(reportId)
                .organisationId(organisationId)
                .build();

        ReportStatusUpdate statusUpdate = new ReportStatusUpdate(
                reportId,
                LedgerDispatchStatus.MARK_DISPATCH,
                null,
                Set.of()
        );

        ReportsLedgerUpdatedEvent event = ReportsLedgerUpdatedEvent.builder()
                .metadata(EventMetadata.create("test"))
                .organisationId(organisationId)
                .statusUpdates(Set.of(statusUpdate))
                .build();

        when(reportingRepository.findAllById(Set.of(reportId))).thenReturn(List.of(reportEntity));

        // When
        reportingEventHandler.handleReportsLedgerUpdated(event);

        // Then
        ArgumentCaptor<ReportEntity> captor = ArgumentCaptor.forClass(ReportEntity.class);
        verify(reportingRepository).save(captor.capture());

        ReportEntity savedReport = captor.getValue();
        assertEquals(LedgerDispatchStatus.MARK_DISPATCH, savedReport.getLedgerDispatchStatus());
        assertNull(savedReport.getBlockchainHash());
        assertNull(savedReport.getBlockchainType());
    }

    @Test
    void handleReportsLedgerUpdated_withMultipleReports_shouldUpdateAll() {
        // Given
        String reportId1 = "report1";
        String reportId2 = "report2";
        String organisationId = "org123";

        ReportEntity reportEntity1 = ReportEntity.builder()
                .id(reportId1)
                .organisationId(organisationId)
                .build();

        ReportEntity reportEntity2 = ReportEntity.builder()
                .id(reportId2)
                .organisationId(organisationId)
                .build();

        BlockchainReceipt receipt1 = new BlockchainReceipt("CARDANO", "hash1");
        BlockchainReceipt receipt2 = new BlockchainReceipt("CARDANO", "hash2");

        ReportStatusUpdate statusUpdate1 = new ReportStatusUpdate(
                reportId1,
                LedgerDispatchStatus.DISPATCHED,
                null,
                Set.of(receipt1)
        );

        ReportStatusUpdate statusUpdate2 = new ReportStatusUpdate(
                reportId2,
                LedgerDispatchStatus.COMPLETED,
                null,
                Set.of(receipt2)
        );

        ReportsLedgerUpdatedEvent event = ReportsLedgerUpdatedEvent.builder()
                .metadata(EventMetadata.create("test"))
                .organisationId(organisationId)
                .statusUpdates(Set.of(statusUpdate1, statusUpdate2))
                .build();

        when(reportingRepository.findAllById(Set.of(reportId1, reportId2)))
                .thenReturn(List.of(reportEntity1, reportEntity2));

        // When
        reportingEventHandler.handleReportsLedgerUpdated(event);

        // Then
        verify(reportingRepository).findAllById(Set.of(reportId1, reportId2));
        verify(reportingRepository, times(2)).save(any(ReportEntity.class));

        assertEquals(LedgerDispatchStatus.DISPATCHED, reportEntity1.getLedgerDispatchStatus());
        assertEquals("hash1", reportEntity1.getBlockchainHash());
        assertEquals("CARDANO", reportEntity1.getBlockchainType());

        assertEquals(LedgerDispatchStatus.COMPLETED, reportEntity2.getLedgerDispatchStatus());
        assertEquals("hash2", reportEntity2.getBlockchainHash());
        assertEquals("CARDANO", reportEntity2.getBlockchainType());
    }

    @Test
    void handleReportsLedgerUpdated_withNoMatchingReports_shouldNotSaveAnything() {
        // Given
        String reportId = "nonexistent";
        String organisationId = "org999";

        ReportStatusUpdate statusUpdate = new ReportStatusUpdate(
                reportId,
                LedgerDispatchStatus.DISPATCHED,
                null,
                Set.of()
        );

        ReportsLedgerUpdatedEvent event = ReportsLedgerUpdatedEvent.builder()
                .metadata(EventMetadata.create("test"))
                .organisationId(organisationId)
                .statusUpdates(Set.of(statusUpdate))
                .build();

        when(reportingRepository.findAllById(Set.of(reportId))).thenReturn(List.of());

        // When
        reportingEventHandler.handleReportsLedgerUpdated(event);

        // Then
        verify(reportingRepository).findAllById(Set.of(reportId));
        verify(reportingRepository, never()).save(any(ReportEntity.class));
    }

    @Test
    void handleReportsLedgerUpdated_withRetryingStatus_shouldUpdateStatus() {
        // Given
        String reportId = "report888";
        String organisationId = "org888";
        String errorReason = "Network timeout, retrying";

        ReportEntity reportEntity = ReportEntity.builder()
                .id(reportId)
                .organisationId(organisationId)
                .ledgerDispatchStatus(LedgerDispatchStatus.MARK_DISPATCH)
                .build();

        ReportStatusUpdate statusUpdate = new ReportStatusUpdate(
                reportId,
                LedgerDispatchStatus.RETRYING,
                errorReason,
                Set.of()
        );

        ReportsLedgerUpdatedEvent event = ReportsLedgerUpdatedEvent.builder()
                .metadata(EventMetadata.create("test"))
                .organisationId(organisationId)
                .statusUpdates(Set.of(statusUpdate))
                .build();

        when(reportingRepository.findAllById(Set.of(reportId))).thenReturn(List.of(reportEntity));

        // When
        reportingEventHandler.handleReportsLedgerUpdated(event);

        // Then
        ArgumentCaptor<ReportEntity> captor = ArgumentCaptor.forClass(ReportEntity.class);
        verify(reportingRepository).save(captor.capture());

        ReportEntity savedReport = captor.getValue();
        assertEquals(LedgerDispatchStatus.RETRYING, savedReport.getLedgerDispatchStatus());
        assertEquals(errorReason, savedReport.getLedgerDispatchStatusErrorReason());
    }
}
