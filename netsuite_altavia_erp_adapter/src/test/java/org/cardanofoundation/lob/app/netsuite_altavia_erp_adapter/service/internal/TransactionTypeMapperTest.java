package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;

class TransactionTypeMapperTest {

    private final TransactionTypeMapper mapper = new TransactionTypeMapper();

    @Test
    void apply_knownCodes_returnCorrectTransactionTypes() {
        assertEquals(Optional.of(TransactionType.CardCharge),    mapper.apply("CardChrg"));
        assertEquals(Optional.of(TransactionType.VendorBill),    mapper.apply("VendBill"));
        assertEquals(Optional.of(TransactionType.CardRefund),    mapper.apply("CardRfnd"));
        assertEquals(Optional.of(TransactionType.Journal),       mapper.apply("Journal"));
        assertEquals(Optional.of(TransactionType.FxRevaluation), mapper.apply("FxReval"));
        assertEquals(Optional.of(TransactionType.Transfer),      mapper.apply("Transfer"));
        assertEquals(Optional.of(TransactionType.CustomerPayment), mapper.apply("CustPymt"));
        assertEquals(Optional.of(TransactionType.CustomerCredit), mapper.apply("CustCred"));
        assertEquals(Optional.of(TransactionType.ExpenseReport), mapper.apply("ExpRept"));
        assertEquals(Optional.of(TransactionType.VendorPayment), mapper.apply("VendPymt"));
        assertEquals(Optional.of(TransactionType.BillCredit),    mapper.apply("VendCred"));
        assertEquals(Optional.of(TransactionType.CustomerInvoice), mapper.apply("CustInvc"));
    }

    @Test
    void apply_unknownCode_returnsEmpty() {
        assertTrue(mapper.apply("Unknown").isEmpty());
        assertTrue(mapper.apply("").isEmpty());
        assertTrue(mapper.apply("INVALID").isEmpty());
    }

}
