package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;

@Getter
@Setter
@AllArgsConstructor
public class TransactionItemView {

    private String id;

    private String accountDebitCode;

    private String accountDebitName;

    private String accountDebitRefCode;

    private String accountCreditCode;

    private String accountCreditName;

    private String accountCreditRefCode;

    private BigDecimal amountFcy;

    private BigDecimal amountLcy;

    private BigDecimal fxRate;

    private String costCenterCustomerCode;

    private String costCenterName;

    private String parentCostCenterCustomerCode;

    private String parentCostCenterName;

    private String projectCustomerCode;

    private String projectName;

    private String parentProjectCustomerCode;

    private String parentProjectName;

    private String accountEventCode;

    private String accountEventName;

    private String documentNum;

    private String documentCurrencyCustomerCode;

    private String vatCustomerCode;

    private BigDecimal vatRate;

    private String counterpartyCustomerCode;

    private String counterpartyType;

    private String counterpartyName;

    private RejectionReason rejectionReason;


}
