package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.FxRevaluation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;

@Slf4j
class ERPSourceTransactionVersionCalculatorTest {

    @Test
    public void testTx1() {
        val org = Organisation.builder()
                .id("401cad588bb2152f5c7ea0646ed84dd7f1b233dc73c3463d721f43e117a0e8ad")
                .build();

        val tx = createTx1(org);

        val tHash = ERPSourceTransactionVersionCalculator.compute(tx);

        assertThat(tHash).isNotNull();
        assertThat(tHash).isEqualTo("2f715f83e6e1cff7ee85ce36caf6fd5557886513222d055d0d9a6207fc343930");
    }

    @Test
    public void testTx1AndTx2() {
        val org = Organisation.builder()
                .id("401cad588bb2152f5c7ea0646ed84dd7f1b233dc73c3463d721f43e117a0e8ad")
                .build();

        val tx1 = createTx1(org);
        val tx1Hash = ERPSourceTransactionVersionCalculator.compute(tx1);

        val tx2 = createTx2(org);
        val tx2Hash = ERPSourceTransactionVersionCalculator.compute(tx2);

        assertThat(tx1Hash).isEqualTo("2f715f83e6e1cff7ee85ce36caf6fd5557886513222d055d0d9a6207fc343930");
        assertThat(tx2Hash).isEqualTo("2f715f83e6e1cff7ee85ce36caf6fd5557886513222d055d0d9a6207fc343930");
    }

    private static TransactionEntity createTx1(Organisation org) {
        val tx = new TransactionEntity();
        tx.setTransactionType(FxRevaluation);
        tx.setEntryDate(LocalDate.of(2021, 1, 1));
        tx.setOrganisation(org);
        tx.setTransactionInternalNumber("FxRevaluation-1");

        val txItem1 = new TransactionItemEntity();
        txItem1.setId("1");
        txItem1.setAmountFcy(BigDecimal.valueOf(0.0));
        txItem1.setAmountLcy(BigDecimal.valueOf(0.0));
        txItem1.setFxRate(BigDecimal.valueOf(2.12345));

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
                                .build())
                .build())
        );

        val txItem2 = new TransactionItemEntity();
        txItem2.setId("2");
        txItem2.setAmountFcy(BigDecimal.valueOf(100.10));
        txItem2.setAmountLcy(BigDecimal.valueOf(100.20));
        txItem2.setFxRate(BigDecimal.valueOf(1.123456)); // one charater more than txItem1 (6)

        txItem2.setAccountDebit(Optional.of(Account.builder()
                .code("2000")
                .name("Cash")
                .refCode("21000")
                .build())
        );

        txItem2.setAccountCredit(Optional.of(Account.builder()
                .code("4000")
                .name("Bank")
                .refCode("r3000")
                .build())
        );

        txItem2.setDocument(Optional.of(Document.builder()
                .num("doc-2")
                .vat(Vat.builder()
                        .customerCode("C200")
                        .build())
                .currency(Currency.builder()
                        .id("ISO_4217:CHF")
                        .customerCode("CHF")
                        .build())
                .counterparty(Counterparty.builder()
                        .customerCode("C200")
                        .build())
                .build())
        );

        tx.setItems(Set.of(txItem1, txItem2));

        return tx;
    }

    public static TransactionEntity createTx2(Organisation organisation) {
        val tx = createTx1(organisation);

        tx.getItems().stream().filter(item -> item.getId().equals("1")).findFirst().ifPresent(item -> {
            item.setAmountLcy(BigDecimal.valueOf(0));
            item.setAmountFcy(BigDecimal.valueOf(0));
        });

        return tx;
    }

    public static TransactionEntity createTx3(Organisation organisation) {
        val tx = createTx1(organisation);

        tx.getItems().stream().filter(item -> item.getId().equals("1")).findFirst().ifPresent(item -> {
            item.setAmountLcy(BigDecimal.valueOf(0));
            item.setAmountFcy(BigDecimal.valueOf(0));
            item.setFxRate(BigDecimal.valueOf(1.1234568369827355));
        });

        return tx;
    }

}
