package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.TX_VALIDATION_ERROR;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Document;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;

@ExtendWith(MockitoExtension.class)
class SanityCheckFieldsTaskItemTest {

    @Mock
    private Validator validator;

    private SanityCheckFieldsTaskItem taskItem;

    @BeforeEach
    public void setUp() {
        taskItem = new SanityCheckFieldsTaskItem(validator);
    }

    @Test
    void testTransactionPassesSanityCheck() {
        TransactionEntity tx = mock(TransactionEntity.class);
        when(validator.validate(tx)).thenReturn(Collections.emptySet());
        when(tx.getInternalTransactionNumber()).thenReturn("1");

        taskItem.run(tx);

        assertThat(tx.getViolations()).isEmpty();
        verify(validator, times(1)).validate(tx);
    }

    @Test
    void testTransactionFailsSanityCheck() {
        Organisation organisation = Organisation.builder()
                .id("org1")
                .currencyId("ISO_4217:USD")
                .build();

        TransactionEntity transaction = new TransactionEntity();
        transaction.setOrganisation(organisation);
        transaction.setInternalTransactionNumber("1");

        Set<ConstraintViolation<TransactionEntity>> violations = new HashSet<>();
        ConstraintViolation<TransactionEntity> violation = mock(ConstraintViolation.class);

        violations.add(violation);

        when(validator.validate(transaction)).thenReturn(violations);

        taskItem.run(transaction);

        assertThat(transaction.getViolations()).isNotEmpty();
        assertThat(transaction.getViolations()).anyMatch(v -> v.getCode() == TX_VALIDATION_ERROR);
    }

    @Test
    void testRun_internalNumberMustBePresent() {
        TransactionEntity tx = new TransactionEntity();
        tx.setInternalTransactionNumber("");
        when(validator.validate(tx)).thenReturn(Collections.emptySet());

        taskItem.run(tx);

        assertThat(tx.getViolations()).hasSize(1);
        assertThat(tx.getViolations().iterator().next().getCode())
                .isEqualTo(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.TX_INTERNAL_NUMBER_MUST_BE_PRESENT);
    }

    @Test
    void testRun_documentNameMustBeSet() {
        TransactionEntity tx = new TransactionEntity();
        tx.setInternalTransactionNumber("1");
        TransactionItemEntity itemEntity = mock(TransactionItemEntity.class);
        Document document = mock(Document.class);

        when(itemEntity.getDocument()).thenReturn(Optional.of(document));
        when(itemEntity.getId()).thenReturn("item1");
        when(itemEntity.getStatus()).thenReturn(TxItemValidationStatus.OK);

        tx.setItems(Set.of(itemEntity));
        taskItem.run(tx);

        assertThat(tx.getViolations()).hasSize(1);
        assertThat(tx.getViolations().iterator().next().getCode())
                .isEqualTo(TransactionViolationCode.DOCUMENT_NAME_MUST_BE_SET);
    }

}
