package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OnChainTransactionSearchRequest(
        @JsonProperty("organisationId") String organisationId,
        @JsonProperty("dateFrom") String dateFrom,
        @JsonProperty("dateTo") String dateTo
) {
}
