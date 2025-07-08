package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus.ERASED_SUM_APPLIED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus.OK;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import lombok.val;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Account;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.AccountEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;

class TxItemsAmountsSummingTaskItemTest {

    private TxItemsAmountsSummingTaskItem txItemsAmountsSummingTaskItem;

    @BeforeEach
    public void setup() {
        this.txItemsAmountsSummingTaskItem = new TxItemsAmountsSummingTaskItem();
    }

    @Test
    void shouldNotCollapseItemsWithDifferentKeysButKeepValidItems() {
        val txItem1 = new TransactionItemEntity();
        txItem1.setId("1:0");
        txItem1.setAccountEvent(Optional.of(AccountEvent.builder().code("e12").build()));
        txItem1.setAmountLcy(BigDecimal.ONE);
        txItem1.setAmountFcy(BigDecimal.TEN);
        txItem1.setOperationType(OperationType.DEBIT);

        val txItem2 = new TransactionItemEntity();
        txItem2.setId("1:1");
        txItem2.setAccountEvent(Optional.of(AccountEvent.builder().code("e1212").build()));
        txItem2.setAmountLcy(BigDecimal.ONE);
        txItem2.setAmountFcy(BigDecimal.TEN);
        txItem2.setOperationType(OperationType.DEBIT);

        val txItem3 = new TransactionItemEntity();
        txItem3.setId("1:2");
        txItem3.setAccountEvent(Optional.of(AccountEvent.builder().code("e12").build())); // Same key as txItem1
        txItem3.setAmountLcy(BigDecimal.ONE);
        txItem3.setAmountFcy(BigDecimal.ONE);
        txItem3.setOperationType(OperationType.DEBIT);

        val items = new HashSet<TransactionItemEntity>();
        items.add(txItem1);
        items.add(txItem2);
        items.add(txItem3); // Valid item to be kept after collapsing

        val transaction = new TransactionEntity();
        transaction.setId("1");
        transaction.setItems(items);

        txItemsAmountsSummingTaskItem.run(transaction);

        assertThat(transaction.getAllItems()).hasSize(3); // Original and collapsed
        assertThat(transaction.getItems()).hasSize(2); // Only one valid item remains (collapsed txItem1 and txItem3)
        assertThat(transaction.getItems()).extracting(TransactionItemEntity::getAmountLcy).containsOnly(BigDecimal.TWO, BigDecimal.ONE);
        assertThat(transaction.getItems()).extracting(TransactionItemEntity::getStatus).containsOnly(OK); // Check valid item is OK
    }

    @Test
    void shouldCollapseItemsWithSameKeys() {
        val txItem1 = new TransactionItemEntity();
        txItem1.setId("1:0");
        txItem1.setAccountEvent(Optional.of(AccountEvent.builder().code("e12").build()));
        txItem1.setAmountLcy(BigDecimal.ONE);
        txItem1.setAmountFcy(BigDecimal.TEN);
        txItem1.setAccountCredit(Optional.ofNullable(Account.builder().code("TheCredit").build()));
        txItem1.setAccountDebit(Optional.ofNullable(Account.builder().code("TheDebit").build()));
        txItem1.setOperationType(OperationType.DEBIT);

        val txItem2 = new TransactionItemEntity();
        txItem2.setId("1:1");
        txItem2.setAccountEvent(Optional.of(AccountEvent.builder().code("e12").build()));
        txItem2.setAmountLcy(BigDecimal.ONE);
        txItem2.setAmountFcy(BigDecimal.TEN);
        txItem2.setAccountCredit(Optional.ofNullable(Account.builder().code("TheCredit").build()));
        txItem2.setAccountDebit(Optional.ofNullable(Account.builder().code("TheDebit").build()));
        txItem2.setOperationType(OperationType.DEBIT);

        val items = new HashSet<TransactionItemEntity>();
        items.add(txItem1);
        items.add(txItem2);

        val transaction = new TransactionEntity();
        transaction.setId("1");
        transaction.setItems(items);

        txItemsAmountsSummingTaskItem.run(transaction);

        assertThat(transaction.getAllItems()).hasSize(2); // Original and collapsed item
        assertThat(transaction.getItems()).hasSize(1); // Only one valid item remains

        assertThat(transaction.getItems()).extracting(TransactionItemEntity::getStatus).containsExactly(OK);
        assertThat(transaction.getItems()).extracting(TransactionItemEntity::getAmountLcy).containsOnly(BigDecimal.TWO);
        assertThat(transaction.getAllItems()).extracting(TransactionItemEntity::getStatus).containsExactly(OK, ERASED_SUM_APPLIED);
    }

    @Test
    void shouldNotCollapseWhenTransactionIsFailed() {
        val txItem1 = new TransactionItemEntity();
        txItem1.setId("1:0");
        txItem1.setAccountEvent(Optional.of(AccountEvent.builder().code("e12").build()));
        txItem1.setAmountLcy(BigDecimal.ONE);
        txItem1.setAmountFcy(BigDecimal.TEN);

        Set<TransactionItemEntity> items = new HashSet<>();
        items.add(txItem1);

        val transaction = new TransactionEntity();
        transaction.setId("1");
        transaction.setItems(items);
        transaction.setAutomatedValidationStatus(FAILED);

        txItemsAmountsSummingTaskItem.run(transaction);

        assertThat(transaction.getAllItems()).hasSize(1); // No change
        assertThat(transaction.getAllItems()).extracting(TransactionItemEntity::getStatus).containsOnly(OK);
        assertThat(transaction.getAutomatedValidationStatus()).isEqualTo(FAILED); // Status should remain FAILED
    }

    @Test
    void shouldNotEraseSinceAccountCodesDiffer() {
        val txItem1 = new TransactionItemEntity();
        txItem1.setId("1:0");
        txItem1.setAccountEvent(Optional.of(AccountEvent.builder().code("e12").build()));
        txItem1.setAmountLcy(BigDecimal.ONE);
        txItem1.setAmountFcy(BigDecimal.TEN);

        val txItem2 = new TransactionItemEntity();
        txItem2.setId("1:1");
        txItem2.setAccountEvent(Optional.of(AccountEvent.builder().code("e1212").build()));
        txItem2.setAmountLcy(BigDecimal.ONE);
        txItem2.setAmountFcy(BigDecimal.TEN);

        val items = new HashSet<TransactionItemEntity>();
        items.add(txItem1);
        items.add(txItem2);

        val transaction = new TransactionEntity();
        transaction.setId("1");
        transaction.setItems(items);

        txItemsAmountsSummingTaskItem.run(transaction);

        assertThat(transaction.getAllItems()).extracting(TransactionItemEntity::getStatus).containsOnly(OK);
        assertThat(transaction.getItems()).extracting(TransactionItemEntity::getAmountLcy).containsOnly(BigDecimal.ONE, BigDecimal.ONE);
    }

}
