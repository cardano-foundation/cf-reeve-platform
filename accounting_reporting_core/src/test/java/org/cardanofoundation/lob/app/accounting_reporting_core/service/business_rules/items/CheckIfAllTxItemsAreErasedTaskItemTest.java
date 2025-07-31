package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.ERP;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.ALL_TX_ITEMS_ERASED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus.ERASED_SELF_PAYMENT;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus.OK;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.ERROR;

import java.util.Set;

import lombok.val;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;

class CheckIfAllTxItemsAreErasedTaskItemTest {

    private PipelineTaskItem taskItem;
    private TransactionEntity transaction;

    @BeforeEach
    void setUp() {
        taskItem = new CheckIfAllTxItemsAreErasedTaskItem();
        transaction = new TransactionEntity();
        transaction.setTransactionInternalNumber("TX-123");
    }

    @Test
    void shouldFailTransactionWhenAllItemsAreErased() {
        // Given: A transaction where all items are erased
        val txItem1 = new TransactionItemEntity();
        txItem1.setStatus(ERASED_SELF_PAYMENT);

        transaction.setAllItems(Set.of(txItem1));

        // When: The task item is run
        taskItem.run(transaction);

        // Then: The transaction should have FAILED validation status
        assertThat(transaction.getAutomatedValidationStatus()).isEqualTo(FAILED);

        // And: A violation should be added to the transaction
        assertThat(transaction.getViolations()).hasSize(1);

        // And: The violation should have the correct properties
        TransactionViolation violation = transaction.getViolations().iterator().next();
        assertThat(violation.getCode()).isEqualTo(ALL_TX_ITEMS_ERASED);
        assertThat(violation.getSeverity()).isEqualTo(ERROR);
        assertThat(violation.getSource()).isEqualTo(ERP);
        assertThat(violation.getProcessorModule()).isEqualTo(CheckIfAllTxItemsAreErasedTaskItem.class.getSimpleName());
        assertThat(violation.getBag()).containsEntry("transactionNumber", "TX-123");
    }

    @Test
    void shouldNotFailTransactionWhenNotAllItemsAreErased() {
        // Given: A transaction where not all items are erased
        val txItem1 = new TransactionItemEntity();
        txItem1.setStatus(ERASED_SELF_PAYMENT);
        txItem1.setId("1:0");

        val txItem2 = new TransactionItemEntity();
        txItem2.setStatus(OK);
        txItem2.setId("1:1");

        transaction.setAllItems(Set.of(txItem1, txItem2));

        // When: The task item is run
        taskItem.run(transaction);

        // Then: The transaction should not have a FAILED validation status
        assertThat(transaction.getAutomatedValidationStatus()).isNotEqualTo(FAILED);

        // And: No violation should be added
        assertThat(transaction.getViolations()).isEmpty();
    }

    @Test
    void shouldNotDuplicateViolationIfRunMultipleTimes() {
        // Given: A transaction where all items are erased
        val txItem1 = new TransactionItemEntity();
        txItem1.setStatus(ERASED_SELF_PAYMENT);

        transaction.setAllItems(Set.of(txItem1));

        // When: The task item is run multiple times
        taskItem.run(transaction);
        taskItem.run(transaction); // Running again

        // Then: Only one violation should be present, even after multiple runs
        assertThat(transaction.getViolations()).hasSize(1);
    }

    @Test
    void shouldNotOverrideExistingFailedStatus() {
        // Given: A transaction that has already failed for a different reason
        transaction.setAutomatedValidationStatus(FAILED);
        val txItem1 = new TransactionItemEntity();
        txItem1.setStatus(ERASED_SELF_PAYMENT);

        transaction.setAllItems(Set.of(txItem1));

        // When: The task item is run
        taskItem.run(transaction);

        // Then: The validation status should remain FAILED
        assertThat(transaction.getAutomatedValidationStatus()).isEqualTo(FAILED);

        // And: No additional violation should be added for the same failure
        assertThat(transaction.getViolations()).hasSize(1);
    }

}
