package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.FxRevaluation;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.Journal;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.COST_CENTER_DATA_NOT_FOUND;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.VALIDATED;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import lombok.val;

import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;

@ExtendWith(MockitoExtension.class)
class CostCenterConversionTaskItemTest {

    private PipelineTaskItem taskItem;

    @Mock
    private OrganisationPublicApiIF organisationPublicApiIF;

    @BeforeEach
    public void setup() {
        this.taskItem = new CostCenterConversionTaskItem(organisationPublicApiIF);
    }

    @Test
    void testNoCostCenterConversionSuccess() {
        val txId = Transaction.id("1", "1");

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));
        // Assume no cost center set as null represents empty Optional

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(Journal);
        tx.setItems(Set.of(txItem1));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(VALIDATED);
        assertThat(tx.getViolations()).isEmpty();
    }

    @Test
    void testCostCenterConversionSuccess() {
        val txId = Transaction.id("1", "1");
        when(organisationPublicApiIF.findCostCenter("1", "1")).thenReturn(Optional.of(new CostCenter(new CostCenter.Id("1", "1"), "Cost Center 1", null, null, null, true)));

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));
        txItem1.setCostCenter(Optional.of(new org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter("1", "Cost Center 1")));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(Journal);
        tx.setItems(Set.of(txItem1));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(VALIDATED);
        assertThat(tx.getViolations()).isEmpty();
    }

    @Test
    void testRunCostCenterNotFound() {
        val txId = Transaction.id("1", "1");

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));
        txItem1.setCostCenter(Optional.of(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter.builder().customerCode("1").build()));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(FxRevaluation);
        tx.setItems(Set.of(txItem1));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).isNotEmpty();
        assertThat(tx.getViolations().iterator().next().getCode()).isEqualTo(COST_CENTER_DATA_NOT_FOUND);
    }

    @Test
    void testMultipleItemsWithMixedOutcomes() {
        val txId = Transaction.id("2", "1");

        Mockito.lenient().when(organisationPublicApiIF.findCostCenter(eq("1"), eq("1"))).thenReturn(Optional.empty());
        Mockito.lenient().when(organisationPublicApiIF.findCostCenter(eq("1"), eq("UNKNOWN"))).thenReturn(Optional.empty());

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));
        txItem1.setCostCenter(Optional.of(new org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter("1","Cost Center 1")));

        val txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(txId, "1"));
        txItem2.setCostCenter(Optional.of(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter.builder().customerCode("UNKNOWN").build()));

        val items = new LinkedHashSet<>();
        items.add(txItem1);
        items.add(txItem2);

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("2");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(Journal);
        tx.setItems(Set.of(txItem1, txItem2));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).hasSize(2);
        assertThat(tx.getViolations().iterator().next().getCode()).isEqualTo(COST_CENTER_DATA_NOT_FOUND);
    }

}
