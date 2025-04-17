package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus.COMPLETED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus.DISPATCHED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus.FINALIZED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.VALIDATED;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionBatchStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;

@ExtendWith(MockitoExtension.class)
class TxBatchStatusCalculatorTest {

    @InjectMocks
    private TxBatchStatusCalculator txBatchStatusCalculator;


    @Test
    void reCalcStatus_Finished() {
        TransactionEntity tE1 = mock(TransactionEntity.class);
        TransactionEntity tE2 = mock(TransactionEntity.class);
        TransactionEntity tE3 = mock(TransactionEntity.class);

        // Mock the behavior of the TransactionEntity objects
        when(tE1.getAutomatedValidationStatus()).thenReturn(VALIDATED);
        when(tE1.getLedgerDispatchStatus()).thenReturn(DISPATCHED);

        when(tE2.getAutomatedValidationStatus()).thenReturn(VALIDATED);
        when(tE2.getLedgerDispatchStatus()).thenReturn(DISPATCHED);

        when(tE3.getAutomatedValidationStatus()).thenReturn(VALIDATED);
        when(tE3.getLedgerDispatchStatus()).thenReturn(DISPATCHED);

        TransactionBatchStatus transactionBatchStatus = txBatchStatusCalculator.reCalcStatus(Set.of(tE1, tE2, tE3), Optional.empty());
        Assertions.assertEquals(TransactionBatchStatus.FINISHED, transactionBatchStatus);

    }

    @Test
    void reCalcStatusComplete() {
        TransactionEntity tE1 = mock(TransactionEntity.class);
        TransactionEntity tE2 = mock(TransactionEntity.class);
        TransactionEntity tE3 = mock(TransactionEntity.class);

        // Mock the behavior of the TransactionEntity objects
        when(tE1.getAutomatedValidationStatus()).thenReturn(VALIDATED);
        when(tE1.getLedgerDispatchStatus()).thenReturn(COMPLETED);

        when(tE2.getAutomatedValidationStatus()).thenReturn(VALIDATED);
        when(tE2.getLedgerDispatchStatus()).thenReturn(COMPLETED);

        when(tE3.getAutomatedValidationStatus()).thenReturn(VALIDATED);
        when(tE3.getLedgerDispatchStatus()).thenReturn(COMPLETED);

        TransactionBatchStatus transactionBatchStatus = txBatchStatusCalculator.reCalcStatus(Set.of(tE1, tE2, tE3), Optional.empty());
        Assertions.assertEquals(TransactionBatchStatus.COMPLETE, transactionBatchStatus);

    }

    @Test
    void reCalcStatusFinalized() {
        TransactionEntity tE1 = mock(TransactionEntity.class);
        TransactionEntity tE2 = mock(TransactionEntity.class);
        TransactionEntity tE3 = mock(TransactionEntity.class);

        // Mock the behavior of the TransactionEntity objects
        when(tE1.getAutomatedValidationStatus()).thenReturn(VALIDATED);
        when(tE1.getLedgerDispatchStatus()).thenReturn(FINALIZED);

        when(tE2.getAutomatedValidationStatus()).thenReturn(VALIDATED);
        when(tE2.getLedgerDispatchStatus()).thenReturn(FINALIZED);

        when(tE3.getAutomatedValidationStatus()).thenReturn(VALIDATED);
        when(tE3.getLedgerDispatchStatus()).thenReturn(FINALIZED);

        TransactionBatchStatus transactionBatchStatus = txBatchStatusCalculator.reCalcStatus(Set.of(tE1, tE2, tE3), Optional.empty());
        Assertions.assertEquals(TransactionBatchStatus.FINALIZED, transactionBatchStatus);

    }

    @Test
    void reCalcStatusProcessing() {
        TransactionEntity tE1 = mock(TransactionEntity.class);
        TransactionEntity tE2 = mock(TransactionEntity.class);
        TransactionEntity tE3 = mock(TransactionEntity.class);

        // Mock the behavior of the TransactionEntity objects
        when(tE1.getAutomatedValidationStatus()).thenReturn(VALIDATED);
        when(tE1.getLedgerDispatchStatus()).thenReturn(DISPATCHED);

        when(tE2.getAutomatedValidationStatus()).thenReturn(VALIDATED);
        when(tE2.getLedgerDispatchStatus()).thenReturn(COMPLETED);

        when(tE3.getAutomatedValidationStatus()).thenReturn(VALIDATED);
        when(tE3.getLedgerDispatchStatus()).thenReturn(FINALIZED);

        TransactionBatchStatus transactionBatchStatus = txBatchStatusCalculator.reCalcStatus(Set.of(tE1, tE2, tE3), Optional.empty());
        Assertions.assertEquals(TransactionBatchStatus.PROCESSING, transactionBatchStatus);

    }

}
