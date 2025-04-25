package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus.*;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;


@Service
@Slf4j
public class TxBatchStatsCalculator {

//    public BatchStatistics reCalcStats(Set<TransactionEntity> transactions,
//                                       Optional<BatchStatistics> existingBatchStatistics,
//                                       Optional<Integer> totalTransactionsCount) {
//
//        return BatchStatistics.builder()
//                .totalTransactionsCount(totalTransactionsCount.orElseGet(() -> existingBatchStatistics.flatMap(BatchStatistics::getTotalTransactionsCount).orElse(0)))
//                .processedTransactionsCount(transactions.size())
//                .approvedTransactionsCount((int) transactions.stream().filter(TransactionEntity::getTransactionApproved).count())
//                .approvedTransactionsDispatchCount((int) transactions.stream().filter(TransactionEntity::getLedgerDispatchApproved).count())
//
//                .dispatchedTransactionsCount((int) transactions.stream().filter(tx -> tx.getLedgerDispatchStatus() == DISPATCHED).count())
//                .completedTransactionsCount((int) transactions.stream().filter(tx -> tx.getLedgerDispatchStatus() == COMPLETED).count())
//                .finalizedTransactionsCount((int) transactions.stream().filter(tx -> tx.getLedgerDispatchStatus() == FINALIZED).count())
//
//                .failedTransactionsCount((int) transactions.stream().filter(tx -> tx.getAutomatedValidationStatus() == FAILED).count())
//
//                .failedSourceLOBTransactionsCount((int) transactions.stream()
//                        .filter(tx -> tx.getAutomatedValidationStatus() == FAILED).count())
//
//                .failedSourceERPTransactionsCount((int) transactions.stream()
//                        .filter(tx -> tx.getAutomatedValidationStatus() == FAILED).count())
//
//                .build();
//    }

}
