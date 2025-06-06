package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;

import java.math.BigDecimal;
import java.time.LocalDate;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonFormat;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.RejectionReason;

@Getter
@Setter
@AllArgsConstructor
public class ExtractionTransactionItemView {

    private String id;

    private String transactionInternalNumber;

    private String transactionID;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate entryDate;

    private TransactionType transactionType;

    @Nullable
    private String blockChainHash;

    private ReconcilationCode reconciliation;

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

    private String costCenterExternalCustomerCode;

    private String costCenterName;

    private String projectCustomerCode;

    private String projectName;

    private String projectExternalCustomerCode;

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

    private String parentCostCenterCustomerCode;

    private String parentCostCenterExternalCustomerCode;

    private String parentCostCenterName;

    private String parentProjectCustomerCode;

    private String parentProjectExternalCustomerCode;

    private String parentProjectName;

}
