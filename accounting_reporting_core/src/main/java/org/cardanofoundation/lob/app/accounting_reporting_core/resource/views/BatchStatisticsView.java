package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.BatchStatistics;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
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
                .readyToApproveTransactions(approve)
                .pendingTransactions(pending)
                .approvedTransactions(publish)
                .publishedTransactions(published)
                .invalidTransactions(invalid)
                .build();
    }

    public static BatchStatisticsView from(String batchId, BatchStatistics batchStatistics) {
        return new BatchStatisticsView(
                batchId,
                batchStatistics.getInvalidTransactions(),
                batchStatistics.getPendingTransactions(),
                batchStatistics.getReadyToApproveTransactions(),
                batchStatistics.getApprovedTransactions(),
                batchStatistics.getPublishedTransactions(),
                batchStatistics.getTotal()
        );
    }

    public void merge(BatchStatisticsView other) {
        this.invalid += other.invalid;
        this.pending += other.pending;
        this.approve += other.approve;
        this.publish += other.publish;
        this.published += other.published;
        this.total += other.total;
    }
}
