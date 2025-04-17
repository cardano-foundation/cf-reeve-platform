package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import java.util.Optional;
import java.util.Set;

import org.mockito.InjectMocks;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.BatchStatistics;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;


@ExtendWith(MockitoExtension.class)
class TxBatchStatsCalculatorTest {

    @InjectMocks
    private TxBatchStatsCalculator txBatchStatsCalculator;

    @Test
    void testReCalcStats_AllStatuses() {
        // Arrange
        TransactionEntity tx1 = createTx(true, true, LedgerDispatchStatus.DISPATCHED, TxValidationStatus.FAILED);
        TransactionEntity tx2 = createTx(true, false, LedgerDispatchStatus.COMPLETED, TxValidationStatus.VALIDATED);
        TransactionEntity tx3 = createTx(false, true, LedgerDispatchStatus.FINALIZED, TxValidationStatus.FAILED);
        TransactionEntity tx4 = createTx(false, false, LedgerDispatchStatus.FINALIZED, TxValidationStatus.FAILED);

        Set<TransactionEntity> transactions = Set.of(tx1, tx2, tx3, tx4);

        BatchStatistics existingStats = BatchStatistics.builder()
                .totalTransactionsCount(42)
                .build();

        // Act
        BatchStatistics result = txBatchStatsCalculator.reCalcStats(
                transactions,
                Optional.of(existingStats),
                Optional.empty()
        );

        // Assert
        Assertions.assertEquals(Optional.of(42), result.getTotalTransactionsCount());
        Assertions.assertEquals(Optional.of(4), result.getProcessedTransactionsCount());
        Assertions.assertEquals(Optional.of(2), result.getApprovedTransactionsCount());
        Assertions.assertEquals(Optional.of(2), result.getApprovedTransactionsDispatchCount());
        Assertions.assertEquals(Optional.of(1), result.getDispatchedTransactionsCount());
        Assertions.assertEquals(Optional.of(1), result.getCompletedTransactionsCount());
        Assertions.assertEquals(Optional.of(2), result.getFinalizedTransactionsCount());
        Assertions.assertEquals(Optional.of(3), result.getFailedTransactionsCount());
        Assertions.assertEquals(Optional.of(3), result.getFailedSourceLOBTransactionsCount());
        Assertions.assertEquals(Optional.of(3), result.getFailedSourceERPTransactionsCount());
    }

    private TransactionEntity createTx(
            boolean approved,
            boolean dispatchApproved,
            LedgerDispatchStatus dispatchStatus,
            TxValidationStatus validationStatus
    ) {
        TransactionEntity tx = Mockito.mock(TransactionEntity.class);
        Mockito.when(tx.getTransactionApproved()).thenReturn(approved);
        Mockito.when(tx.getLedgerDispatchApproved()).thenReturn(dispatchApproved);
        Mockito.when(tx.getLedgerDispatchStatus()).thenReturn(dispatchStatus);
        Mockito.when(tx.getAutomatedValidationStatus()).thenReturn(validationStatus);
        return tx;
    }
}
