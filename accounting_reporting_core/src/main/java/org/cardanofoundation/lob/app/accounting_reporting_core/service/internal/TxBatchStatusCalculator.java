package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus.*;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.VALIDATED;

import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionBatchStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;

@Service
@Slf4j
public class TxBatchStatusCalculator {

    public TransactionBatchStatus reCalcStatus(Set<TransactionEntity> transactions,
                                               Optional<Integer> totalTransactionsCount) {

        long validTransactionsCount = transactions
                .stream()
                .filter(transactionEntity -> transactionEntity.getAutomatedValidationStatus() == VALIDATED)
                .count();

        long dispatchedTransactionsCount = transactions
                .stream()
                .filter(transactionEntity -> transactionEntity.getLedgerDispatchStatus() == DISPATCHED)
                .count();

        long completedTransactionsCount = transactions
                .stream()
                .filter(transactionEntity -> transactionEntity.getLedgerDispatchStatus() == COMPLETED)
                .count();

        long finalisedTransactionsCount = transactions
                .stream()
                .filter(transactionEntity -> transactionEntity.getLedgerDispatchStatus() == FINALIZED)
                .count();

        if (dispatchedTransactionsCount == validTransactionsCount) {
            return TransactionBatchStatus.FINISHED;
        }
        if (completedTransactionsCount == validTransactionsCount) {
            return TransactionBatchStatus.COMPLETE;
        }
        if (finalisedTransactionsCount == validTransactionsCount) {
            return TransactionBatchStatus.FINALIZED;
        }

        return TransactionBatchStatus.PROCESSING;
    }

}
