package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import java.util.Optional;
import java.util.function.Function;

import lombok.extern.slf4j.Slf4j;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;

@Slf4j
public class TransactionTypeMapper implements Function<String, Optional<TransactionType>> {

    public Optional<TransactionType> apply(String transType) {
        return switch(transType) {
            case "CardChrg" -> Optional.of(TransactionType.CardCharge);
            case "VendBill" -> Optional.of(TransactionType.VendorBill);
            case "CardRfnd" -> Optional.of(TransactionType.CardRefund);
            case "Journal" -> Optional.of(TransactionType.Journal);
            case "FxReval" -> Optional.of(TransactionType.FxRevaluation);
            case "Transfer" -> Optional.of(TransactionType.Transfer);
            case "CustPymt" -> Optional.of(TransactionType.CustomerPayment);
            case "ExpRept" -> Optional.of(TransactionType.ExpenseReport);
            case "VendPymt" -> Optional.of(TransactionType.VendorPayment);
            case "VendCred" -> Optional.of(TransactionType.BillCredit);
            case "CustInvc" -> Optional.of(TransactionType.CustomerInvoice);
            default -> Optional.empty();
        };
    }

}
