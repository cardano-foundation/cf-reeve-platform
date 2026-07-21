package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation;

public class TransactionEntityTest {

    private final AtomicInteger idSequence = new AtomicInteger();

    // TransactionItemEntity#equals/hashCode is based solely on id, so each item
    // needs a distinct id or the enclosing Set silently collapses "equal" items.
    private TransactionItemEntity item(BigDecimal amountLcy, OperationType operationType) {
        TransactionItemEntity item = new TransactionItemEntity();
        item.setId("item-" + idSequence.incrementAndGet());
        item.setAmountLcy(amountLcy);
        item.setOperationType(operationType);
        return item;
    }

    private TransactionViolation netOffViolation() {
        return TransactionViolation.builder()
                .code(TransactionViolationCode.NET_OFF_TX)
                .severity(Violation.Severity.ERROR)
                .source(Source.ERP)
                .processorModule("test")
                .build();
    }

    // Journal transactions

    @Test
    public void journal_withDebitItems_sumsDebitItemsOnly() {
        Set<TransactionItemEntity> items = new LinkedHashSet<>();
        items.add(item(BigDecimal.valueOf(100), OperationType.DEBIT));
        items.add(item(BigDecimal.valueOf(50), OperationType.DEBIT));
        items.add(item(BigDecimal.valueOf(999), OperationType.CREDIT));

        TransactionEntity tx = TransactionEntity.builder()
                .id("tx1")
                .transactionType(TransactionType.Journal)
                .items(items)
                .build();

        BigDecimal result = tx.getAmountLcyTotalForAllDebitItems();

        assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(150));
    }

    @Test
    public void journal_withNoDebitItems_fallsBackToCreditItems() {
        // Regression test for LOB-12007: Total Amount displayed 0 even though
        // line items (all CREDIT, no DEBIT) contained values.
        Set<TransactionItemEntity> items = new LinkedHashSet<>();
        items.add(item(BigDecimal.valueOf(75), OperationType.CREDIT));
        items.add(item(BigDecimal.valueOf(25), OperationType.CREDIT));

        TransactionEntity tx = TransactionEntity.builder()
                .id("tx2")
                .transactionType(TransactionType.Journal)
                .items(items)
                .build();

        BigDecimal result = tx.getAmountLcyTotalForAllDebitItems();

        assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    public void journal_withNoItemsAtAll_returnsZero() {
        TransactionEntity tx = TransactionEntity.builder()
                .id("tx3")
                .transactionType(TransactionType.Journal)
                .items(new LinkedHashSet<>())
                .build();

        BigDecimal result = tx.getAmountLcyTotalForAllDebitItems();

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    public void journal_withNetOffViolation_returnsZeroRegardlessOfItems() {
        Set<TransactionItemEntity> items = new LinkedHashSet<>();
        items.add(item(BigDecimal.valueOf(100), OperationType.DEBIT));

        Set<TransactionViolation> violations = new LinkedHashSet<>();
        violations.add(netOffViolation());

        TransactionEntity tx = TransactionEntity.builder()
                .id("tx4")
                .transactionType(TransactionType.Journal)
                .items(items)
                .violations(violations)
                .build();

        BigDecimal result = tx.getAmountLcyTotalForAllDebitItems();

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // FxRevaluation transactions

    @Test
    public void fxRevaluation_returnsAbsoluteDifferenceBetweenCreditAndDebit() {
        Set<TransactionItemEntity> items = new LinkedHashSet<>();
        items.add(item(BigDecimal.valueOf(120), OperationType.CREDIT));
        items.add(item(BigDecimal.valueOf(50), OperationType.DEBIT));

        TransactionEntity tx = TransactionEntity.builder()
                .id("tx5")
                .transactionType(TransactionType.FxRevaluation)
                .items(items)
                .build();

        BigDecimal result = tx.getAmountLcyTotalForAllDebitItems();

        assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(70));
    }

    @Test
    public void fxRevaluation_debitGreaterThanCredit_returnsAbsoluteValue() {
        Set<TransactionItemEntity> items = new LinkedHashSet<>();
        items.add(item(BigDecimal.valueOf(30), OperationType.CREDIT));
        items.add(item(BigDecimal.valueOf(90), OperationType.DEBIT));

        TransactionEntity tx = TransactionEntity.builder()
                .id("tx6")
                .transactionType(TransactionType.FxRevaluation)
                .items(items)
                .build();

        BigDecimal result = tx.getAmountLcyTotalForAllDebitItems();

        assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(60));
    }

    // Other transaction types (default branch)

    @Test
    public void otherTransactionType_sumsAllItemsAbsolute() {
        Set<TransactionItemEntity> items = new LinkedHashSet<>();
        items.add(item(BigDecimal.valueOf(40), OperationType.DEBIT));
        items.add(item(BigDecimal.valueOf(-10), OperationType.CREDIT));

        TransactionEntity tx = TransactionEntity.builder()
                .id("tx7")
                .transactionType(TransactionType.VendorBill)
                .items(items)
                .build();

        BigDecimal result = tx.getAmountLcyTotalForAllDebitItems();

        assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(30));
    }

    @Test
    public void updateTotalAmountLcy_setsTotalAmountLcyField() {
        Set<TransactionItemEntity> items = new LinkedHashSet<>();
        items.add(item(BigDecimal.valueOf(15), OperationType.CREDIT));

        TransactionEntity tx = TransactionEntity.builder()
                .id("tx8")
                .transactionType(TransactionType.Journal)
                .items(items)
                .build();

        tx.updateTotalAmountLcy();

        assertThat(tx.getTotalAmountLcy()).isEqualByComparingTo(BigDecimal.valueOf(15));
    }
}
