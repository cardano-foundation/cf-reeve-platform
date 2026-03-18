package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import lombok.val;

import org.junit.jupiter.api.Test;

class TransactionTypeMapperTest {

    @Test
    void testApply() {
        val transactionTypeMapper = TransactionTypeMapper.createTypeToNumber();

        assertEquals(1, transactionTypeMapper.apply(CardCharge));
        assertEquals(2, transactionTypeMapper.apply(VendorBill));
        assertEquals(4, transactionTypeMapper.apply(CardRefund));
        assertEquals(8, transactionTypeMapper.apply(Journal));
        assertEquals(16, transactionTypeMapper.apply(FxRevaluation));
        assertEquals(32, transactionTypeMapper.apply(Transfer));
        assertEquals(64, transactionTypeMapper.apply(CustomerPayment));
        assertEquals(128, transactionTypeMapper.apply(ExpenseReport));
        assertEquals(256, transactionTypeMapper.apply(VendorPayment));
        assertEquals(512, transactionTypeMapper.apply(BillCredit));
        assertEquals(1024, transactionTypeMapper.apply(CustomerInvoice));
        assertEquals(2048, transactionTypeMapper.apply(Unknown));
        assertEquals(4096, transactionTypeMapper.apply(CustomerCredit));
    }

}
