package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus.*;

import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionProcessingStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchStatisticsView;


@Service
@Slf4j
public class TxBatchStatsCalculator {

    public BatchStatisticsView calculateBatchStatisticsView(String batchId, Set<TransactionEntity> entities) {
        return new BatchStatisticsView(batchId,
                entities.stream().map(tx -> isStatus(tx.getProcessingStatus(), TransactionProcessingStatus.INVALID))
                        .reduce(0, Integer::sum),
                entities.stream().map(tx -> isStatus(tx.getProcessingStatus(), TransactionProcessingStatus.PENDING))
                        .reduce(0, Integer::sum),
                entities.stream().map(tx -> isStatus(tx.getProcessingStatus(), TransactionProcessingStatus.APPROVE))
                        .reduce(0, Integer::sum),
                entities.stream().map(tx -> isStatus(tx.getProcessingStatus(), TransactionProcessingStatus.PUBLISH))
                        .reduce(0, Integer::sum),
                entities.stream().map(tx -> isStatus(tx.getProcessingStatus(), TransactionProcessingStatus.PUBLISHED))
                        .reduce(0, Integer::sum),
                entities.size()
                );
    }

    private int isStatus(Optional<TransactionProcessingStatus> status, TransactionProcessingStatus expectedStatus) {
        return status.map(s -> s.equals(expectedStatus) ? 1 : 0).orElse(0);
    }

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
