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

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
import org.cardanofoundation.lob.app.reporting.job.ReprocessJob;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;

@ExtendWith(MockitoExtension.class)
class ReportingEventHandlerTest {

    @Mock
    private ReportingRepository reportingRepository;

    @Mock
    private ReprocessJob reprocessJob;

    @InjectMocks
    private ReportingEventHandler reportingEventHandler;

    private LedgerUpdatedEvent reportEvent(String organisationId, LedgerStatusUpdate... updates) {
        return LedgerUpdatedEvent.builder()
                .organisationId(organisationId)
                .type(LedgerUpdateType.REPORT)
                .statusUpdates(Set.of(updates))
                .build();
    }

    @Test
    void handleReportsLedgerUpdated_withValidEvent_shouldUpdateReport() {
        String reportId = "report123";
        String organisationId = "org123";

        ReportEntity reportEntity = ReportEntity.builder()
                .id(reportId)
                .organisationId(organisationId)
                .build();

        BlockchainReceipt receipt = new BlockchainReceipt("CARDANO", "hash123");

        LedgerStatusUpdate statusUpdate = new LedgerStatusUpdate(
                reportId,
                LedgerDispatchStatus.DISPATCHED,
                null,
                Set.of(receipt)
        );

        when(reportingRepository.findAllById(Set.of(reportId))).thenReturn(List.of(reportEntity));

        reportingEventHandler.handleLedgerUpdated(reportEvent(organisationId, statusUpdate));

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
        String reportId = "report456";
        String organisationId = "org456";
        String errorReason = "Transaction failed";

        ReportEntity reportEntity = ReportEntity.builder()
                .id(reportId)
                .organisationId(organisationId)
                .build();

        LedgerStatusUpdate statusUpdate = new LedgerStatusUpdate(
                reportId,
                LedgerDispatchStatus.FAILED,
                errorReason,
                Set.of()
        );

        when(reportingRepository.findAllById(Set.of(reportId))).thenReturn(List.of(reportEntity));

        reportingEventHandler.handleLedgerUpdated(reportEvent(organisationId, statusUpdate));

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
        String reportId = "report789";
        String organisationId = "org789";

        ReportEntity reportEntity = ReportEntity.builder()
                .id(reportId)
                .organisationId(organisationId)
                .build();

        LedgerStatusUpdate statusUpdate = new LedgerStatusUpdate(
                reportId,
                LedgerDispatchStatus.MARK_DISPATCH,
                null,
                Set.of()
        );

        when(reportingRepository.findAllById(Set.of(reportId))).thenReturn(List.of(reportEntity));

        reportingEventHandler.handleLedgerUpdated(reportEvent(organisationId, statusUpdate));

        ArgumentCaptor<ReportEntity> captor = ArgumentCaptor.forClass(ReportEntity.class);
        verify(reportingRepository).save(captor.capture());

        ReportEntity savedReport = captor.getValue();
        assertEquals(LedgerDispatchStatus.MARK_DISPATCH, savedReport.getLedgerDispatchStatus());
        assertNull(savedReport.getBlockchainHash());
        assertNull(savedReport.getBlockchainType());
    }

    @Test
    void handleReportsLedgerUpdated_withMultipleReports_shouldUpdateAll() {
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

        LedgerStatusUpdate statusUpdate1 = new LedgerStatusUpdate(
                reportId1,
                LedgerDispatchStatus.DISPATCHED,
                null,
                Set.of(receipt1)
        );

        LedgerStatusUpdate statusUpdate2 = new LedgerStatusUpdate(
                reportId2,
                LedgerDispatchStatus.COMPLETED,
                null,
                Set.of(receipt2)
        );

        when(reportingRepository.findAllById(Set.of(reportId1, reportId2)))
                .thenReturn(List.of(reportEntity1, reportEntity2));

        reportingEventHandler.handleLedgerUpdated(reportEvent(organisationId, statusUpdate1, statusUpdate2));

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
        String reportId = "nonexistent";
        String organisationId = "org999";

        LedgerStatusUpdate statusUpdate = new LedgerStatusUpdate(
                reportId,
                LedgerDispatchStatus.DISPATCHED,
                null,
                Set.of()
        );

        when(reportingRepository.findAllById(Set.of(reportId))).thenReturn(List.of());

        reportingEventHandler.handleLedgerUpdated(reportEvent(organisationId, statusUpdate));

        verify(reportingRepository).findAllById(Set.of(reportId));
        verify(reportingRepository, never()).save(any(ReportEntity.class));
    }

    @Test
    void handleReportsLedgerUpdated_withRetryingStatus_shouldUpdateStatus() {
        String reportId = "report888";
        String organisationId = "org888";
        String errorReason = "Network timeout, retrying";

        ReportEntity reportEntity = ReportEntity.builder()
                .id(reportId)
                .organisationId(organisationId)
                .ledgerDispatchStatus(LedgerDispatchStatus.MARK_DISPATCH)
                .build();

        LedgerStatusUpdate statusUpdate = new LedgerStatusUpdate(
                reportId,
                LedgerDispatchStatus.RETRYING,
                errorReason,
                Set.of()
        );

        when(reportingRepository.findAllById(Set.of(reportId))).thenReturn(List.of(reportEntity));

        reportingEventHandler.handleLedgerUpdated(reportEvent(organisationId, statusUpdate));

        ArgumentCaptor<ReportEntity> captor = ArgumentCaptor.forClass(ReportEntity.class);
        verify(reportingRepository).save(captor.capture());

        ReportEntity savedReport = captor.getValue();
        assertEquals(LedgerDispatchStatus.RETRYING, savedReport.getLedgerDispatchStatus());
        assertEquals(errorReason, savedReport.getLedgerDispatchStatusErrorReason());
    }
}
