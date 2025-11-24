package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.sort;

import lombok.Getter;

@Getter
public enum TransactionFieldSortRequest {
    BATCH_ID("id"),
    IMPORTED_ON("createdAt"),
    IMPORTED_BY("createdBy"),
    NUMBER_OF_TRANSACTIONS("batchStatistics.total");

    private final String code;

    TransactionFieldSortRequest(String code) {
        this.code = code;
    }

}
