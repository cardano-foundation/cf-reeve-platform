package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;


import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.Type.VENDOR;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.Set;

import lombok.val;

import net.jqwik.api.*;
import net.jqwik.time.api.Dates;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;

class TransactionVersionPropertyBasedTest {

    @Provide
    Arbitrary<TransactionType> transactionTypes() {
        return Arbitraries.of(TransactionType.values());
    }

    @Provide
    Arbitrary<LocalDate> entryDates() {
        return Dates.dates().between(LocalDate.of(2000, 1, 1), LocalDate.of(2030, 12, 31));
    }

    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.bigDecimals().between(BigDecimal.valueOf(0.01), BigDecimal.valueOf(1000000));
    }

    @Provide
    Arbitrary<String> organisationIds() {
        return Arbitraries.strings().withCharRange('0', '9').ofLength(64);
    }

    @Property
    void compute_shouldChangeHashForDifferentFields(@ForAll("transactionTypes") TransactionType transactionType1,
                                                    @ForAll("transactionTypes") TransactionType transactionType2,
                                                    @ForAll("entryDates") LocalDate entryDate1,
                                                    @ForAll("entryDates") LocalDate entryDate2,
                                                    @ForAll("amounts") BigDecimal amountFcy1,
                                                    @ForAll("amounts") BigDecimal amountFcy2,
                                                    @ForAll("organisationIds") String organisationId1,
                                                    @ForAll("organisationIds") String organisationId2) {
        // Ensure different values
        Assume.that(!transactionType1.equals(transactionType2));
        Assume.that(!entryDate1.equals(entryDate2));
        Assume.that(!amountFcy1.equals(amountFcy2));
        Assume.that(!organisationId1.equals(organisationId2));

        // Base transaction setup
        val org1 = Organisation.builder().id(organisationId1).build();
        val org2 = Organisation.builder().id(organisationId2).build();

        val txItem1 = new TransactionItemEntity();
        txItem1.setId("1");
        txItem1.setAmountFcy(amountFcy1);
        txItem1.setAccountDebit(Optional.of(Account.builder()
                .code("1000")
                .name("Cash")
                .refCode("r1000")
                .build()
        ));
        txItem1.setAccountCredit(Optional.of(Account.builder()
                .code("2000")
                .name("Bank")
                .refCode("r2000")
                .build()
        ));
        txItem1.setDocument(Optional.of(Document.builder()
                .num("doc-1")
                .vat(Vat.builder()
                        .customerCode("C100")
                        .build())
                .currency(Currency.builder()
                        .id("ISO_4217:CHF")
                        .customerCode("CHF")
                        .build())
                .counterparty(Counterparty.builder()
                        .customerCode("C100")
                        .type(VENDOR)
                        .build())
                .build()
        ));

        val t1 = new TransactionEntity();
        t1.setTransactionType(transactionType1);
        t1.setAccountingPeriod(YearMonth.of(2021, 1));
        t1.setEntryDate(entryDate1);
        t1.setOrganisation(org1);
        t1.setTransactionInternalNumber("FxRevaluation-1");
        t1.setItems(Set.of(txItem1));

        val tHash1 = ERPSourceTransactionVersionCalculator.compute(t1);

        // Change transaction type
        t1.setTransactionType(transactionType2);
        val tHash2 = ERPSourceTransactionVersionCalculator.compute(t1);

        assertThat(tHash1).isNotNull();
        assertThat(tHash2).isNotNull();
        assertThat(tHash1).isNotEqualTo(tHash2);

        // Reset to original type and change entry date
        t1.setTransactionType(transactionType1);
        t1.setEntryDate(entryDate2);
        val tHash3 = ERPSourceTransactionVersionCalculator.compute(t1);

        assertThat(tHash3).isNotNull();
        assertThat(tHash3).isNotEqualTo(tHash1);

        // Reset to original entry date and change amount
        t1.setEntryDate(entryDate1);
        txItem1.setAmountFcy(amountFcy2);
        val tHash4 = ERPSourceTransactionVersionCalculator.compute(t1);

        assertThat(tHash4).isNotNull();
        assertThat(tHash4).isNotEqualTo(tHash1);

        // Reset amount and change organisation ID
        txItem1.setAmountFcy(amountFcy1);
        t1.setOrganisation(org2);
        val tHash5 = ERPSourceTransactionVersionCalculator.compute(t1);

        assertThat(tHash5).isNotNull();
        assertThat(tHash5).isNotEqualTo(tHash1);
    }

}
