package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.*;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.ACCOUNT_CODE_CREDIT_IS_EMPTY;
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
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;

public class AccountCodeCreditCheckTaskItemTest {

    private PipelineTaskItem taskItem;

    @BeforeEach
    public void setup() {
        this.taskItem = new AccountCodeCreditCheckTaskItem();
    }

    @Test
    // If we have credit amount then it should be fine
    public void testCreditWorks() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        txItem.setAccountCredit(Optional.of(Account.builder().code("1").build()));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder().id("1").build());
        tx.setTransactionType(FxRevaluation);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(VALIDATED);
        assertThat(tx.getViolations()).isEmpty();
    }

    @Test
    // If we don't have credit amount then error should be raised
    public void testAccountCreditCheckError() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder().id("1").build());
        tx.setTransactionType(FxRevaluation);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).hasSize(1);
        assertThat(tx.getViolations().iterator().next().getCode()).isEqualTo(ACCOUNT_CODE_CREDIT_IS_EMPTY);
    }

    @Test
    // We skip transaction type: JOURNAL from this check
    public void testAccountCreditCheckSkipJournals() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder().id("1").build());
        tx.setTransactionType(Journal);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(VALIDATED);
        assertThat(tx.getViolations()).isEmpty();
    }

    // Multiple Items with Mixed Credit Status
    @Test
    public void testMixedCreditItems() {
        val txId = Transaction.id("2", "1");

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));

        txItem1.setAccountCredit(Optional.of(Account.builder().code("100").build()));

        val txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(txId, "2"));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder().id("1").build());
        tx.setTransactionType(FxRevaluation);
        tx.setItems(Set.of(txItem1, txItem2));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).hasSize(1);
    }

    // Multiple Items, All Without Credit
    @Test
    public void testAllItemsWithoutCredit() {
        val txId = Transaction.id("2", "1");

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));

        val txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(txId, "2"));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder().id("1").build());
        tx.setTransactionType(FxRevaluation);
        tx.setItems(Set.of(txItem1, txItem2));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).hasSize(2);
    }

    // Item with Empty String as Credit
    @Test
    public void testItemWithEmptyStringCredit() {
        val txId = Transaction.id("2", "1");

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));
        txItem1.setAccountCredit(Optional.of(Account.builder().code(" ").build()));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder().id("1").build());
        tx.setTransactionType(BillCredit);
        tx.setItems(Set.of(txItem1));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).hasSize(1);
    }

    // Transaction with No Items
    @Test
    public void testTransactionWithNoItems() {
        val txId = Transaction.id("2", "1");

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder().id("1").build());
        tx.setTransactionType(BillCredit);
        tx.setItems(Set.of());

        taskItem.run(tx);

        assertThat(tx.getViolations()).isEmpty();
    }

    // Valid Credit with Whitespace
    @Test
    public void testValidCreditWithWhitespace() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));
        txItem.setAccountCredit(Optional.of(Account.builder().code(" 100 ").build()));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder().id("1").build());
        tx.setTransactionType(FxRevaluation);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(VALIDATED);
        assertThat(tx.getViolations()).isEmpty();
    }

}
