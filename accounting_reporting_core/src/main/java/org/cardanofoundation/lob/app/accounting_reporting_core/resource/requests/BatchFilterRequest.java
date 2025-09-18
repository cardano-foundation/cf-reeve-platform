package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class BatchFilterRequest {

    @Schema(description = "Search of a specific internal transaction number.")
    private String internalTransactionNumber;
    private List<TransactionType> transactionTypes;
    @Schema(description = "List of document numbers to filter for. If this list is provided, only transactions with a document number in this list are returned.")
    private List<String> documentNumbers;
    @Schema(description = "Document number to filter for. If this field is provided, only transactions with a document number containing this value are returned.")
    private String documentNumber;
    private List<String> currencyCustomerCodes;
    private BigDecimal minFCY;
    private BigDecimal maxFCY;
    private BigDecimal minLCY;
    private BigDecimal maxLCY;
    private BigDecimal minTotalLcy;
    private BigDecimal maxTotalLcy;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private List<String> vatCustomerCodes;
    private List<String> parentCostCenterCustomerCodes;
    private List<String> costCenterCustomerCodes;
    private List<String> counterPartyCustomerCodes;
    private List<String> counterPartyTypes;
    private List<String> debitAccountCodes;
    private List<String> creditAccountCodes;
    private List<String> eventCodes;
    private List<String> parentProjectCustomerCodes;
    private List<String> projectCustomerCodes;
}
