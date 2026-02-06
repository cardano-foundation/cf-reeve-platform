package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OnChainTransactionDto(
        @JsonProperty("id") String id,
        @JsonProperty("tx_hash") String txHash,
        @JsonProperty("internal_number") String internalNumber,
        @JsonProperty("accounting_period") String accountingPeriod,
        @JsonProperty("batch_id") String batchId,
        @JsonProperty("type") String type,
        @JsonProperty("date") String date,
        @JsonProperty("organisation_id") String organisationId,
        @JsonProperty("items") List<OnChainTransactionItemDto> items
) {
}
