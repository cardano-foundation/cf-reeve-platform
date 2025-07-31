package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.AMOUNT_LCY_IS_ZERO;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.VALIDATED;

import java.math.BigDecimal;
import java.util.Set;

import lombok.val;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;

class AmountsLcyCheckTaskItemTest {

    private PipelineTaskItem taskItem;

    @BeforeEach
    public void setup() {
        this.taskItem = new AmountsLcyCheckTaskItem();
    }

    @Test
    void whenLcyIsZeroAndFcyIsNonZero_thenViolationGenerated() {
        val txId = Transaction.id("1", "1");
        val orgId = "1";

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));
        txItem1.setAmountLcy(BigDecimal.ZERO);
        txItem1.setAmountFcy(BigDecimal.valueOf(100));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(Organisation.builder().id(orgId).build());
        tx.setItems(Set.of(txItem1));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).isNotEmpty();
        assertThat(tx.getViolations().iterator().next().getCode()).isEqualTo(AMOUNT_LCY_IS_ZERO);
    }

    @Test
    void whenLcyAndFcyAreNonZero_thenNoViolations() {
        val txId = Transaction.id("2", "1");

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "1"));
        txItem1.setAmountLcy(BigDecimal.valueOf(100));
        txItem1.setAmountFcy(BigDecimal.valueOf(100));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("2");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setItems(Set.of(txItem1));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(VALIDATED);
        assertThat(tx.getViolations()).isEmpty();
    }

    @Test
    void whenBothLcyAndFcyAreZero_thenNoViolations() {
        val txId = Transaction.id("3", "1");

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "2"));
        txItem1.setAmountLcy(BigDecimal.ZERO);
        txItem1.setAmountFcy(BigDecimal.ZERO);

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("3");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setItems(Set.of(txItem1));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(VALIDATED);
        assertThat(tx.getViolations()).isEmpty();
    }

}
