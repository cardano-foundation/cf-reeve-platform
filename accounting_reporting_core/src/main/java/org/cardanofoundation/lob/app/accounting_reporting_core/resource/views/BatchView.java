package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionBatchStatus;

/**
 * Rename the class name BatchView
 */
@Getter
@Setter
@AllArgsConstructor
public class BatchView {
    // ",\n\"organisationId\": \"75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94\",\n\"status\": \"PROCESSING\",\n\"batchStatistics\": {\n\"totalTransactionsCount\": 235,\n\"processedTransactionsCount\": 235,\n\"failedTransactionsCount\": 2,\n\"failedSourceERPTransactionsCount\": 2,\n\"failedSourceLOBTransactionsCount\": 2,\n\"approvedTransactionsCount\": 235,\n\"approvedTransactionsDispatchCount\": 235,\n\"dispatchedTransactionsCount\": 0,\n\"completedTransactionsCount\": 0,\n\"finalizedTransactionsCount\": 0\n },\n\"filteringParameters\": {\n\"transactionTypes\": [\n\"CardCharge\",\n\"VendorBill\",\n\"VendorPayment\",\n\"BillCredit\"\n ],\n\"from\": \"2013-01-02\",\n\"to\": \"2024-05-01\",\n\"accountingPeriodFrom\": \"2021-06\",\n\"accountingPeriodTo\": \"2024-06\",\n\"transactionNumbers\": [\n\"CARDCH565\",\n\"CARDCHRG159\",\n\"CARDHY777\",\n\"VENDBIL119\"\n ]\n },\n\"transactions\": []\n  }]"
    @Schema(example = "f346cc734fe3008ac5fc19b41c7c779690bc69320c97f3a5618554159802fe12")
    private String id;

    @Schema(example = "2024-06-11T12:09:52.962632")
    private String createdAt;

    @Schema(example = "2024-06-11T12:09:58.707360")
    private String updatedAt;

    @Schema(example = "2024-06-11T12:09:52.962632")
    private String createdBy;

    @Schema(example = "2024-06-11T12:09:52.962632")
    private String updateBy;

    @Schema(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
    private String organisationId;

    @Schema(implementation = TransactionBatchStatus.class)
    private TransactionBatchStatus status;

    @Schema(implementation = BatchStatisticsView.class)
    private BatchStatisticsView batchStatistics;

    @Schema(implementation = FilteringParametersView.class)
    private FilteringParametersView filteringParameters;

    @ArraySchema(arraySchema = @Schema(implementation = TransactionView.class))
    private List<TransactionView> transactions = new ArrayList<>();

    private Map<String, Object> details;

    private Long totalTransactionsCount;
}
