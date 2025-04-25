package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.BatchStatistics;

@Getter
@Setter
@AllArgsConstructor
public class BatchStatisticsView {

    private String batchId;

    private int invalid;

    private int pending;

    private int approve;

    private int publish;

    private int published;

    private int total;

    public BatchStatistics toBatchStatistics(int totalTransactions) {
        return BatchStatistics.builder()
                .total(totalTransactions)
                .processedTransactions(total)
                .pendingTransactions(pending)
                .approvedTransactions(approve)
                .publishedTransactions(publish)
                .invalidTransactions(invalid)
                .build();
    }

    public static BatchStatisticsView from(String batchId, BatchStatistics batchStatistics) {
        return new BatchStatisticsView(
                batchId,
                batchStatistics.getInvalidTransactions(),
                batchStatistics.getPendingTransactions(),
                batchStatistics.getProcessedTransactions()
                        - batchStatistics.getInvalidTransactions()
                        - batchStatistics.getPendingTransactions()
                        - batchStatistics.getPublishedTransactions()
                        - batchStatistics.getApprovedTransactions(),
                batchStatistics.getApprovedTransactions(),
                batchStatistics.getPublishedTransactions(),
                batchStatistics.getTotal()
        );
    }
}
