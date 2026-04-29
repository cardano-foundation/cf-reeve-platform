package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchChunkCommittedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.job.TxStatusUpdaterJob;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.ValidateIngestionResponseWaiter;

@ExtendWith(MockitoExtension.class)
class AccountingCoreEventHandlerTest {

    @Mock
    private ERPIncomingDataProcessor erpIncomingDataProcessor;

    @Mock
    private TransactionConverter transactionConverter;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private TransactionBatchService transactionBatchService;

    @Mock
    private TransactionReconcilationService transactionReconcilationService;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private TxStatusUpdaterJob txStatusUpdaterJob;

    @Mock
    private ValidateIngestionResponseWaiter validateIngestionResponseWaiter;

    @InjectMocks
    private AccountingCoreEventHandler handler;

    @Test
    void scheduleStatusUpdateForPrimaryBatchOfTransactionAfterCommit() {
        var event = TransactionBatchChunkCommittedEvent.builder()
                .batchId("batch-test")
                .processedTransactionCount(5)
                .batchesToBeUpdated(Set.of())
                .build();

        handler.handleTransactionBatchChunkCommittedEvent(event);

        verify(transactionBatchService).updateTransactionBatchStatusAndStats(
                "batch-test",
                5,
                Optional.empty()
        );
    }

    @Test
    void scheduleStatusUpdateForRelatedBatchesAfterCommit() {
        var event = TransactionBatchChunkCommittedEvent.builder()
                .batchId("batch-test")
                .processedTransactionCount(3)
                .batchesToBeUpdated(Set.of("related-batch"))
                .build();

        handler.handleTransactionBatchChunkCommittedEvent(event);

        verify(transactionBatchService).updateTransactionBatchStatusAndStats(
                "related-batch",
                null,
                Optional.empty()
        );
    }
}
