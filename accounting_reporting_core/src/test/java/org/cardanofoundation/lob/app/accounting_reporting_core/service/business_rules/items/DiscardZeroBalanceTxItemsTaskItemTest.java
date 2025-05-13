package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus.*;

import java.math.BigDecimal;
import java.util.LinkedHashSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;

class DiscardZeroBalanceTxItemsTaskItemTest {

    private PipelineTaskItem taskItem;

    @BeforeEach
    public void setup() {
        this.taskItem = new DiscardZeroBalanceTxItemsTaskItem();
    }

    @Test
    void testNoDiscard() {
        String txId = Transaction.id("1", "1");

        TransactionItemEntity txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));
        txItem1.setAmountLcy(BigDecimal.valueOf(0));
        txItem1.setAmountFcy(BigDecimal.valueOf(100));
        txItem1.setStatus(OK);

        TransactionItemEntity txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(txId, "1"));
        txItem2.setAmountLcy(BigDecimal.valueOf(200));
        txItem2.setAmountFcy(BigDecimal.valueOf(0));
        txItem2.setStatus(OK);

        TransactionItemEntity txItem3 = new TransactionItemEntity();
        txItem3.setId(TransactionItem.id(txId, "2"));
        txItem3.setAmountLcy(BigDecimal.valueOf(300));
        txItem3.setAmountFcy(BigDecimal.valueOf(300));
        txItem3.setStatus(OK);

        LinkedHashSet<TransactionItemEntity> txItems = new LinkedHashSet<TransactionItemEntity>();
        txItems.add(txItem1);
        txItems.add(txItem2);
        txItems.add(txItem3);

        TransactionEntity tx = new TransactionEntity();
        tx.setId(txId);
        tx.setItems(txItems);

        taskItem.run(tx);

        assertThat(tx.getItems()).hasSize(3);
        assertThat(tx.getItems().stream().map(TransactionItemEntity::getAmountLcy)).containsExactlyInAnyOrder(BigDecimal.valueOf(0), BigDecimal.valueOf(200), BigDecimal.valueOf(300));
        assertThat(tx.getItems().stream().map(TransactionItemEntity::getAmountFcy)).containsExactlyInAnyOrder(BigDecimal.valueOf(100), BigDecimal.valueOf(0), BigDecimal.valueOf(300));

        // Ensure all non-zero balance items have status OK
        assertThat(tx.getItems().stream().allMatch(item -> item.getStatus() == OK)).isTrue();
    }

    @Test
    public void testDiscardTxItemsWithZeroBalance() {
        String txId = Transaction.id("1", "1");

        TransactionItemEntity txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));
        txItem1.setAmountLcy(BigDecimal.valueOf(0));
        txItem1.setAmountFcy(BigDecimal.valueOf(0));
        txItem1.setStatus(OK);

        TransactionItemEntity txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(txId, "1"));
        txItem2.setAmountLcy(BigDecimal.valueOf(200));
        txItem2.setAmountFcy(BigDecimal.valueOf(200));
        txItem2.setStatus(OK);

        LinkedHashSet<TransactionItemEntity> txItems = new LinkedHashSet<TransactionItemEntity>();
        txItems.add(txItem1);
        txItems.add(txItem2);

        TransactionEntity tx = new TransactionEntity();
        tx.setId(txId);
        tx.setItems(txItems);

        taskItem.run(tx);

        // Check that the zero-balance item is marked as ERASED
        assertThat(txItem1.getStatus()).isEqualTo(ERASED_ZERO_BALANCE);
        // Check that the non-zero balance item remains OK
        assertThat(txItem2.getStatus()).isEqualTo(OK);
    }

    @Test
    void testDiscardAllTxItemsWithZeroBalance() {
        String txId = Transaction.id("2", "1");

        TransactionItemEntity txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));
        txItem1.setAmountLcy(BigDecimal.ZERO);
        txItem1.setAmountFcy(BigDecimal.ZERO);
        txItem1.setStatus(OK);

        TransactionItemEntity txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(txId, "1"));
        txItem2.setAmountLcy(BigDecimal.ZERO);
        txItem2.setAmountFcy(BigDecimal.ZERO);
        txItem2.setStatus(OK);

        LinkedHashSet<TransactionItemEntity> txItems = new LinkedHashSet<TransactionItemEntity>();
        txItems.add(txItem1);
        txItems.add(txItem2);

        TransactionEntity tx = new TransactionEntity();
        tx.setId(txId);
        tx.setItems(txItems);

        taskItem.run(tx);

        // Ensure all zero-balance items are marked as ERASED
        assertThat(tx.getItems().stream().allMatch(item -> item.getStatus() == ERASED_ZERO_BALANCE)).isTrue();
    }

    @Test
    void testNoItemsToDiscard() {
        TransactionEntity tx = new TransactionEntity();
        tx.setItems(new LinkedHashSet<>());

        taskItem.run(tx);

        // Ensure no errors occur and the item list remains empty
        assertThat(tx.getItems()).isEmpty();
    }

    @Test
    void discardViolation() {
        String txId = Transaction.id("3", "1");

        TransactionItemEntity txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));
        txItem1.setAmountLcy(BigDecimal.ZERO);
        txItem1.setAmountFcy(BigDecimal.ZERO);
        txItem1.setStatus(OK);

        LinkedHashSet<TransactionViolation> violations = new LinkedHashSet<TransactionViolation>();
        violations.add(TransactionViolation.builder().txItemId(txItem1.getId()).build());

        LinkedHashSet<TransactionItemEntity> txItems = new LinkedHashSet<TransactionItemEntity>();
        txItems.add(txItem1);

        TransactionEntity tx = new TransactionEntity();
        tx.setId(txId);
        tx.setItems(txItems);
        tx.setViolations(violations);

        taskItem.run(tx);

        // Ensure the violation is removed
        assertThat(tx.getViolations()).isEmpty();
    }

}
