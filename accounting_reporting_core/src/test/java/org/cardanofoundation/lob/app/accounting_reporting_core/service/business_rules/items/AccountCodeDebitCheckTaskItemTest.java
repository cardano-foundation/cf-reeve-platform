package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.BillCredit;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.FxRevaluation;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.ACCOUNT_CODE_DEBIT_IS_EMPTY;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.VALIDATED;

import java.util.Optional;
import java.util.Set;

import lombok.val;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Account;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;

class AccountCodeDebitCheckTaskItemTest {

    private PipelineTaskItem taskItem;

    @BeforeEach
    public void setup() {
        this.taskItem = new AccountCodeDebitCheckTaskItem();
    }

    @Test
    public void testDebitWorks() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));
        txItem.setAccountDebit(Optional.of(Account.builder().code("1").build()));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(FxRevaluation);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(VALIDATED);
        assertThat(tx.getViolations()).isEmpty();
    }

    @Test
    public void testAccountDebitCheckError() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(BillCredit);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).hasSize(1);
        assertThat(tx.getViolations().iterator().next().getCode()).isEqualTo(ACCOUNT_CODE_DEBIT_IS_EMPTY);
    }

    @Test
    public void testAccountDebitCheckSkipFxRevaluation() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(FxRevaluation);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(VALIDATED);
        assertThat(tx.getViolations()).isEmpty();
    }

    // Multiple Items with Mixed Debit Status
    @Test
    public void testMixedDebitItems() {
        val txId = Transaction.id("2", "1");

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "1"));
        txItem1.setAccountDebit(Optional.of(Account.builder().code("100").build()));

        val txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(txId, "2"));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("2");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(BillCredit);
        tx.setItems(Set.of(txItem1, txItem2));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).hasSize(1);
    }

    // All Items without Debit
    @Test
    public void testAllItemsWithoutDebit() {
        val txId = Transaction.id("3", "1");

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "3"));

        val txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(txId, "4"));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("3");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(BillCredit);
        tx.setItems(Set.of(txItem1, txItem2));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).hasSize(2);
    }

    // Transaction with No Items
    @Test
    public void testTransactionWithNoItems() {
        val txId = Transaction.id("5", "1");

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("5");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(BillCredit);
        tx.setItems(Set.of());

        taskItem.run(tx);

        assertThat(tx.getViolations()).isEmpty();
    }

    // Valid Debit with Whitespace
    @Test
    public void testValidDebitWithWhitespace() {
        val txId = Transaction.id("6", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "6"));
        txItem.setAccountDebit(Optional.of(Account.builder().code(" 100 ").build()));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("6");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(BillCredit);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(VALIDATED);
        assertThat(tx.getViolations()).isEmpty();
    }

}
