package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OnChainTransactionItemDto(
        @JsonProperty("id") String id,
        @JsonProperty("amountFcy") BigDecimal amountFcy,
        @JsonProperty("fxRate") String fxRate,
        @JsonProperty("documentNumber") String documentNumber,
        @JsonProperty("currency") String currency,
        @JsonProperty("costCenterName") String costCenterName,
        @JsonProperty("costCenterCustCode") String costCenterCustCode,
        @JsonProperty("vatRate") String vatRate,
        @JsonProperty("vatCustCode") String vatCustCode,
        @JsonProperty("eventCode") String eventCode,
        @JsonProperty("eventName") String eventName,
        @JsonProperty("projectCustCode") String projectCustCode,
        @JsonProperty("projectName") String projectName
) {
}
