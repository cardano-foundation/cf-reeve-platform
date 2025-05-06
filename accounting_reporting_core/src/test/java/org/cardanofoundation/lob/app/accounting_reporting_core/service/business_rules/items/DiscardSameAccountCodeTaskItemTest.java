package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Account;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;

class DiscardSameAccountCodeTaskItemTest {

    DiscardSameAccountCodeTaskItem discardSameAccountCodeTaskItem = new DiscardSameAccountCodeTaskItem();

    @Test
    void emptyListOfItems() {
        TransactionEntity tx = mock(TransactionEntity.class);
        when(tx.getItems()).thenReturn(Set.of());

        discardSameAccountCodeTaskItem.run(tx);

        // Verify that the status of the items is not changed
        when(tx.getItems()).thenReturn(Set.of());
    }

    @Test
    void noDiscards() {
        TransactionEntity tx = mock(TransactionEntity.class);
        TransactionItemEntity item1 = mock(TransactionItemEntity.class);
        when(tx.getItems()).thenReturn(Set.of(item1));
        when(item1.getAccountDebit()).thenReturn(Optional.of(Account.builder().code("123").build()));
        when(item1.getAccountCredit()).thenReturn(Optional.of(Account.builder().code("456").build()));
        discardSameAccountCodeTaskItem.run(tx);

        // Verify that the status of the items is not changed
        when(tx.getItems()).thenReturn(Set.of(item1));
    }

    @Test
    void discard() {
        TransactionEntity tx = mock(TransactionEntity.class);
        TransactionItemEntity item1 = mock(TransactionItemEntity.class);
        when(tx.getItems()).thenReturn(Set.of(item1));
        when(item1.getAccountDebit()).thenReturn(Optional.of(Account.builder().code("123").build()));
        when(item1.getAccountCredit()).thenReturn(Optional.of(Account.builder().code("123").build()));
        discardSameAccountCodeTaskItem.run(tx);

        // Verify that the status of the items is changed
        when(tx.getItems()).thenReturn(Set.of());
    }

    @Test
    void removeNoViolation() {
        TransactionEntity tx = mock(TransactionEntity.class);
        TransactionItemEntity item1 = mock(TransactionItemEntity.class);
        when(tx.getItems()).thenReturn(Set.of(item1));
        when(item1.getId()).thenReturn("item1");
        when(item1.getAccountDebit()).thenReturn(Optional.of(Account.builder().code("123").build()));
        when(item1.getAccountCredit()).thenReturn(Optional.of(Account.builder().code("123").build()));
        LinkedHashSet <TransactionViolation> violations = new LinkedHashSet<>();
        violations.add(TransactionViolation.builder().txItemId("item2").build());
        when(tx.getViolations()).thenReturn(violations);
        discardSameAccountCodeTaskItem.run(tx);

        // Verify that the status of the items is changed
        when(tx.getItems()).thenReturn(Set.of(item1));
        when(tx.getViolations()).thenReturn(Set.of(TransactionViolation.builder().txItemId("item2").build()));
    }

    @Test
    void removeViolation() {
        TransactionEntity tx = mock(TransactionEntity.class);
        TransactionItemEntity item1 = mock(TransactionItemEntity.class);
        when(tx.getItems()).thenReturn(Set.of(item1));
        when(item1.getId()).thenReturn("item1");
        when(item1.getAccountDebit()).thenReturn(Optional.of(Account.builder().code("123").build()));
        when(item1.getAccountCredit()).thenReturn(Optional.of(Account.builder().code("123").build()));
        LinkedHashSet <TransactionViolation> violations = new LinkedHashSet<>();
        violations.add(TransactionViolation.builder().txItemId("item1").build());
        when(tx.getViolations()).thenReturn(violations);
        discardSameAccountCodeTaskItem.run(tx);

        // Verify that the status of the items is changed
        when(tx.getItems()).thenReturn(Set.of(item1));
        when(tx.getViolations()).thenReturn(Set.of());
    }
}
