package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class TransactionItemAggregateView {

    private String txId;
    private BigDecimal amountLcyAggregated;
    private BigDecimal amountFcyAggregated;

}
