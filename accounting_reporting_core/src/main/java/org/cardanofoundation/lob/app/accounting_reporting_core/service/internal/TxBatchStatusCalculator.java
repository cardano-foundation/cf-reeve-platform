package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus.*;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionBatchStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchStatisticsView;

@Service
@Slf4j
public class TxBatchStatusCalculator {

    public TransactionBatchStatus reCalcStatus(BatchStatisticsView view,
                                               int totalTransactionsCount) {
        TransactionBatchStatus status = TransactionBatchStatus.PROCESSING;
        if(totalTransactionsCount == view.getTotal()) {
            status = TransactionBatchStatus.FINISHED;
        }

        if(totalTransactionsCount == view.getPublished()) {
            status = TransactionBatchStatus.FINALIZED; // All transaction are published
        }

        return status;
    }

}
