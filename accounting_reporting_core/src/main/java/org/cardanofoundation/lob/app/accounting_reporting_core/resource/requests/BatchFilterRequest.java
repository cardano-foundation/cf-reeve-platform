package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class BatchFilterRequest {

    private List<TransactionType> transactionTypes;
    private List<String> documentNumbers;
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
}
