package org.cardanofoundation.lob.app.accounting_reporting_core.resource.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OnChainTransactionDto;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OnChainTransactionsPageResponse(
        @JsonProperty("content") List<OnChainTransactionDto> content,
        @JsonProperty("total_elements") long totalElements,
        @JsonProperty("total_pages") int totalPages,
        @JsonProperty("last") boolean last,
        @JsonProperty("size") int size,
        @JsonProperty("number") int number,
        @JsonProperty("first") boolean first,
        @JsonProperty("empty") boolean empty
) {
}
