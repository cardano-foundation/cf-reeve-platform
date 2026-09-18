package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.BillCredit;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.FxRevaluation;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.Journal;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.ACCOUNT_CODE_CREDIT_IS_EMPTY;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.ACCOUNT_CODE_DEBIT_IS_EMPTY;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.VALIDATED;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import lombok.val;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Account;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;

@ExtendWith(MockitoExtension.class)
class LineItemDebitCreditAccountsPresentCheckTaskItemTest {

    @Mock
    private OrganisationPublicApiIF organisationPublicApiIF;
    @Mock
    private org.cardanofoundation.lob.app.organisation.domain.entity.Organisation organisation;

    private PipelineTaskItem taskItem;

    @BeforeEach
    void setup() {
        this.taskItem = new LineItemDebitCreditAccountsPresentCheckTaskItem();
    }

    // Single line item with both a debit and a credit account code, and no offsetting
    // second line, must be valid on its own (no reliance on transaction-level amount totals).
    @Test
    void testSingleLineItemWithBothAccountsIsValid() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));
        txItem.setOperationType(OperationType.DEBIT);
        txItem.setAccountDebit(Optional.of(Account.builder().code("6312110100").build()));
        txItem.setAccountCredit(Optional.of(Account.builder().code("2101110101").build()));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setInternalTransactionNumber("1");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(BillCredit);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(VALIDATED);
        assertThat(tx.getViolations()).isEmpty();
    }

    @Test
    void testMissingDebitAccountRaisesViolation() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));
        txItem.setAccountCredit(Optional.of(Account.builder().code("2101110101").build()));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setInternalTransactionNumber("1");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(BillCredit);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).hasSize(1);
        assertThat(tx.getViolations().iterator().next().getCode()).isEqualTo(ACCOUNT_CODE_DEBIT_IS_EMPTY);
    }

    @Test
    void testMissingCreditAccountRaisesViolation() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));
        txItem.setAccountDebit(Optional.of(Account.builder().code("6312110100").build()));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setInternalTransactionNumber("1");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(BillCredit);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).hasSize(1);
        assertThat(tx.getViolations().iterator().next().getCode()).isEqualTo(ACCOUNT_CODE_CREDIT_IS_EMPTY);
    }

    @Test
    void testMissingBothAccountsRaisesTwoViolations() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setInternalTransactionNumber("1");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(BillCredit);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).hasSize(2);
    }

    // Same-account "dummy" lines are handled by DiscardSameAccountCodeTaskItem later in the
    // pipeline; at this stage they still carry both codes (just equal), so they must not fail here.
    @Test
    void testSameDebitAndCreditAccountIsValidAtThisStage() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));
        txItem.setAccountDebit(Optional.of(Account.builder().code("0000000000").build()));
        txItem.setAccountCredit(Optional.of(Account.builder().code("0000000000").build()));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setInternalTransactionNumber("1");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(BillCredit);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(VALIDATED);
        assertThat(tx.getViolations()).isEmpty();
    }

    // No transaction-type carve-outs: applies uniformly, including to Journal and FxRevaluation.
    @Test
    void testAppliesToJournalTransactions() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));
        txItem.setAccountDebit(Optional.of(Account.builder().code("6312110100").build()));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setInternalTransactionNumber("1");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(Journal);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).hasSize(1);
        assertThat(tx.getViolations().iterator().next().getCode()).isEqualTo(ACCOUNT_CODE_CREDIT_IS_EMPTY);
    }

    @Test
    void testAppliesToFxRevaluationTransactions() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));
        txItem.setAccountCredit(Optional.of(Account.builder().code("2101110101").build()));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setInternalTransactionNumber("1");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(FxRevaluation);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).hasSize(1);
        assertThat(tx.getViolations().iterator().next().getCode()).isEqualTo(ACCOUNT_CODE_DEBIT_IS_EMPTY);
    }

    @Test
    void testTransactionWithNoItems() {
        val txId = Transaction.id("1", "1");

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setInternalTransactionNumber("1");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(BillCredit);
        tx.setItems(Set.of());

        taskItem.run(tx);

        assertThat(tx.getViolations()).isEmpty();
    }

    // Pipeline ordering regression: in real NetSuite/CSV data, Journal-type rows arrive with a
    // blank Credit Code by design and rely on JournalAccountCreditEnrichmentTaskItem (which must
    // run first, in the same preValidationPipelineTask stage) to fill it with the org's dummy
    // account. If this check ran before enrichment, every Journal transaction would be wrongly
    // flagged as missing a credit account.
    @Test
    void testJournalLineIsValidAfterEnrichmentRunsFirst() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));
        txItem.setOperationType(OperationType.DEBIT);
        txItem.setAccountDebit(Optional.of(Account.builder().code("0000000000").build()));
        txItem.clearAccountCodeCredit();

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setInternalTransactionNumber("1");
        tx.setOrganisation(Organisation.builder().id("org1").build());
        tx.setTransactionType(Journal);
        tx.setItems(new LinkedHashSet<>(Set.of(txItem)));

        when(organisationPublicApiIF.findByOrganisationId("org1")).thenReturn(Optional.of(organisation));
        when(organisation.getDummyAccount()).thenReturn(Optional.of("0000000000"));

        new JournalAccountCreditEnrichmentTaskItem(organisationPublicApiIF).run(tx);
        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(VALIDATED);
        assertThat(tx.getViolations()).isEmpty();
    }

    @Test
    void testDisabledRuleDoesNothing() {
        val txId = Transaction.id("1", "1");

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setInternalTransactionNumber("1");
        tx.setOrganisation(Organisation.builder().id("1").build());
        tx.setTransactionType(BillCredit);
        tx.setItems(Set.of(txItem));

        new LineItemDebitCreditAccountsPresentCheckTaskItem(false).run(tx);

        assertThat(tx.getViolations()).isEmpty();
    }

}
