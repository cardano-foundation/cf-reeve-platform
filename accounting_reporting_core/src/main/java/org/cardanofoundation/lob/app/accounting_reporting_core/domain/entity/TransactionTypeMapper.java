package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity;

import java.util.function.Function;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;

public final class TransactionTypeMapper {

    public static Function<TransactionType, Integer> createTypeToNumber() {
        return type -> {
            return switch(type) {
                case CardCharge -> 1;
                case VendorBill -> 2;
                case CardRefund -> 4;
                case Journal -> 8;
                case FxRevaluation -> 16;
                case Transfer -> 32;
                case CustomerPayment -> 64;
                case ExpenseReport -> 128;
                case VendorPayment -> 256;
                case BillCredit -> 512;
                case CustomerInvoice -> 1024;
                case Unknown -> 2048;
            };
        };
    }

}
