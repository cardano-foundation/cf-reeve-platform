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

        if(totalTransactionsCount == view.getTotal()) {
            return TransactionBatchStatus.FINISHED;
        }
        if(totalTransactionsCount == view.getTotal() && view.getApprove() == 0 && view.getInvalid() == 0 && view.getPending() == 0) {
            return TransactionBatchStatus.COMPLETE; // All transaction are approved
        }

        if(totalTransactionsCount == view.getPublished()) {
            return TransactionBatchStatus.FINALIZED; // All transaction are published
        }

        return TransactionBatchStatus.PROCESSING;
    }

}
