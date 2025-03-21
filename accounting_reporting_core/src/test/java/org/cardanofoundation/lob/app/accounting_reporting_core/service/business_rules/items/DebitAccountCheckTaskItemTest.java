package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;

import java.util.LinkedHashSet;
import java.util.Optional;

import lombok.val;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Account;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;

public class DebitAccountCheckTaskItemTest {

    private PipelineTaskItem taskItem;

    @BeforeEach
    public void setup() {
        this.taskItem = new DiscardSameAccountCodeTaskItem();
    }

    @Test
    void testRunWithoutCollapsing() {
        val txId = Transaction.id("1", "1");

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));

        txItem1.setAccountCredit(Optional.of(Account.builder()
                .code("1")
                .build())
        );

        txItem1.setAccountDebit(Optional.of(Account.builder()
                .code("2")
                .build())
        );

        val txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(txId, "1"));

        txItem2.setAccountCredit(Optional.of(Account.builder()
                .code("1")
                .build())
        );

        txItem2.setAccountDebit(Optional.of(Account.builder()
                .code("2")
                .build())
        );

        val txItems = new LinkedHashSet<TransactionItemEntity>();
        txItems.add(txItem1);
        txItems.add(txItem2);

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setItems(txItems);

        taskItem.run(tx);

        assertThat(tx.getItems()).hasSize(2).allMatch(item -> {
            return !item.getAccountDebit().equals(item.getAccountCredit());
        });
    }

    @Test
    void testRunWithCollapsing() {
        val txId = Transaction.id("1", "1");

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));

        txItem1.setAccountCredit(Optional.of(Account.builder()
                .code("1")
                .build())
        );

        txItem1.setAccountDebit(Optional.of(Account.builder()
                .code("1")
                .build())
        );

        val txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(txId, "1"));

        txItem2.setAccountCredit(Optional.of(Account.builder()
                .code("1")
                .build())
        );

        txItem2.setAccountDebit(Optional.of(Account.builder()
                .code("2")
                .build())
        );

        val txItems = new LinkedHashSet<TransactionItemEntity>();
        txItems.add(txItem1);
        txItems.add(txItem2);

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setItems(txItems);

        taskItem.run(tx);

        assertThat(tx.getItems()).hasSize(1);
        assertThat(tx.getItems()).extracting(TransactionItemEntity::getId).containsOnly(TransactionItem.id(txId, "1"));
    }

    @Test
    void testRunWithCollapsingWithFailedTransactions() {
        val txId = Transaction.id("1", "1");

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));

        txItem1.setAccountCredit(Optional.of(Account.builder()
                .code("1")
                .build()
        ));

        txItem1.setAccountDebit(Optional.of(Account.builder()
                .code("1")
                .build()
        ));

        val txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(txId, "1"));

        txItem2.setAccountCredit(Optional.of(Account.builder()
                .code("1")
                .build()
        ));

        txItem2.setAccountDebit(Optional.of(Account.builder()
                .code("2")
                .build()
        ));

        val txItems = new LinkedHashSet<TransactionItemEntity>();
        txItems.add(txItem1);
        txItems.add(txItem2);

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setAutomatedValidationStatus(FAILED);
        tx.setItems(txItems);

        taskItem.run(tx);

        assertThat(tx.getItems()).hasSize(1);
        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
    }

    @Test
    public void testMixedValidAndInvalidAccountCodes() {
        val txId = Transaction.id("2", "1");

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));

        txItem1.setAccountCredit(Optional.of(Account.builder()
                .code("3")
                .build()
        ));

        txItem1.setAccountDebit(Optional.of(Account.builder()
                .code("3")
                .build()
        ));

        val txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(txId, "1"));
        txItem2.setAccountCredit(Optional.of(Account.builder()
                .code("4")
                .build()
        ));

        txItem2.setAccountDebit(Optional.of(Account.builder()
                .code("5")
                .build()
        ));

        val txItems = new LinkedHashSet<TransactionItemEntity>();
        txItems.add(txItem1);
        txItems.add(txItem2);

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setItems(txItems);

        taskItem.run(tx);

        assertThat(tx.getItems()).hasSize(1);
        assertThat(tx.getItems().stream().findFirst().orElseThrow().getAccountDebit().map(Account::getCode).orElseThrow()).isEqualTo("5");
        assertThat(tx.getItems().stream().findFirst().orElseThrow().getAccountCredit().map(Account::getCode).orElseThrow()).isEqualTo("4");
    }

    @Test
    public void testTransactionItemsWithMissingAccountCodes() {
        val txId = Transaction.id("3", "1");

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));
        txItem1.clearAccountCodeCredit();

        txItem1.setAccountCredit(Optional.of(Account.builder()
                .code("6")
                .build()
        ));

        val txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(txId, "1"));

        txItem2.setAccountCredit(Optional.of(Account.builder()
                .code("7")
                .build()
        ));

        txItem2.clearAccountCodeDebit();

        val txItems = new LinkedHashSet<TransactionItemEntity>();
        txItems.add(txItem1);
        txItems.add(txItem2);

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setItems(txItems);

        taskItem.run(tx);

        assertThat(tx.getItems()).hasSize(2);
    }

    @Test
    public void testTransactionsWithNoItems() {
        val txId = Transaction.id("4", "1");

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setItems(new LinkedHashSet<>());

        taskItem.run(tx);

        assertThat(tx.getItems()).isEmpty();
    }

}
