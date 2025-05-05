package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.TX_VALIDATION_ERROR;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import lombok.val;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;

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
        TransactionEntity tx = new TransactionEntity();
        when(validator.validate(tx)).thenReturn(Collections.emptySet());

        taskItem.run(tx);

        assertThat(tx.getViolations()).isEmpty();
        verify(validator, times(1)).validate(tx);
    }

    @Test
    void testTransactionFailsSanityCheck() {
        val organisation = Organisation.builder()
                .id("org1")
                .currencyId("ISO_4217:USD")
                .build();

        val transaction = new TransactionEntity();
        transaction.setOrganisation(organisation);
        transaction.setTransactionInternalNumber("1");

        Set<ConstraintViolation<TransactionEntity>> violations = new HashSet<>();
        ConstraintViolation<TransactionEntity> violation = mock(ConstraintViolation.class);

        violations.add(violation);

        when(validator.validate(transaction)).thenReturn(violations);

        taskItem.run(transaction);

        assertThat(transaction.getViolations()).isNotEmpty();
        assertThat(transaction.getViolations()).anyMatch(v -> v.getCode() == TX_VALIDATION_ERROR);
    }

}
