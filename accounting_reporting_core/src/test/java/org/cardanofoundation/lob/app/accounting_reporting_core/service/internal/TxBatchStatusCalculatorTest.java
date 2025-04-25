package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;



import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionBatchStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchStatisticsView;



@ExtendWith(MockitoExtension.class)
class TxBatchStatusCalculatorTest {

    @InjectMocks
    private TxBatchStatusCalculator txBatchStatusCalculator;

    @Test
    void testFinished() {
        BatchStatisticsView view = new BatchStatisticsView();
        view.setTotal(10);

        TransactionBatchStatus transactionBatchStatus = txBatchStatusCalculator.reCalcStatus(view, 10);

        Assertions.assertEquals(TransactionBatchStatus.FINISHED, transactionBatchStatus);
    }

    @Test
    void testFinalized() {
        BatchStatisticsView view = new BatchStatisticsView();
        view.setTotal(10);
        view.setPublished(10);
        TransactionBatchStatus transactionBatchStatus = txBatchStatusCalculator.reCalcStatus(view, 10);

        Assertions.assertEquals(TransactionBatchStatus.FINALIZED, transactionBatchStatus);
    }

}
