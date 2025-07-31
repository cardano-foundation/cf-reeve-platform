package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import lombok.val;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Document;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;

class DocumentMustBePresentTaskItemTest {

    private DocumentMustBePresentTaskItem taskItem;

    @BeforeEach
    void setUp() {
        taskItem = new DocumentMustBePresentTaskItem();
    }

    @Test
    void shouldNotModifyTransactionWhenAllDocumentsPresent() {
        val document = Document.builder().build();

        val itemWithDocument = new TransactionItemEntity();
        itemWithDocument.setId("itemWithDocument");
        itemWithDocument.setDocument(Optional.of(document));

        val items = new HashSet<TransactionItemEntity>();
        items.add(itemWithDocument);

        val transaction = new TransactionEntity();
        transaction.setTransactionInternalNumber("txn123");
        transaction.setItems(items);
        transaction.setViolations(new HashSet<>());

        taskItem.run(transaction);

        assertThat(transaction.getViolations()).isEmpty();
        assertThat(transaction.getAutomatedValidationStatus()).isNotEqualTo(FAILED);
    }

    @Test
    void shouldAddViolationWhenDocumentIsMissing() {
        val itemWithoutDocument = new TransactionItemEntity();
        itemWithoutDocument.setId("itemWithoutDocument");
        itemWithoutDocument.setDocument(Optional.empty());

        val items = new HashSet<TransactionItemEntity>();
        items.add(itemWithoutDocument);

        val transaction = new TransactionEntity();
        transaction.setTransactionInternalNumber("txn123");
        transaction.setItems(items);
        transaction.setViolations(new HashSet<>());

        taskItem.run(transaction);

        assertThat(transaction.getViolations()).isNotEmpty();
        assertThat(transaction.getAutomatedValidationStatus()).isEqualTo(FAILED);
    }

    @Test
    void shouldHandleMixedDocumentPresenceCorrectly() {
        val document = Document.builder().build();

        val itemWithDocument = new TransactionItemEntity();
        itemWithDocument.setId("itemWithDocument");
        itemWithDocument.setDocument(Optional.of(document));

        val itemWithoutDocument = new TransactionItemEntity();
        itemWithoutDocument.setId("itemWithoutDocument");
        itemWithoutDocument.setDocument(Optional.empty());

        Set<TransactionItemEntity> items = new LinkedHashSet<>();
        items.add(itemWithDocument);
        items.add(itemWithoutDocument);

        val transaction = new TransactionEntity();
        transaction.setTransactionInternalNumber("txn123");
        transaction.setItems(items);
        transaction.setViolations(new HashSet<>());

        taskItem.run(transaction);

        assertThat(transaction.getViolations()).hasSize(1);
        assertThat(transaction.getAutomatedValidationStatus()).isEqualTo(FAILED);
    }

    @Test
    void shouldNotAddViolationForEmptyItems() {
        val transaction = new TransactionEntity();
        transaction.setTransactionInternalNumber("txn123");
        transaction.setItems(new HashSet<>());
        transaction.setViolations(new HashSet<>());

        taskItem.run(transaction);

        assertThat(transaction.getViolations()).isEmpty();
        assertThat(transaction.getAutomatedValidationStatus()).isNotEqualTo(FAILED);
    }

}
