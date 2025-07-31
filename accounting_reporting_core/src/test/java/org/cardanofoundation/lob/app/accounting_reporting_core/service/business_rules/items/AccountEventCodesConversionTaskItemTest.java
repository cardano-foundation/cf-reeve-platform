package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.CHART_OF_ACCOUNT_NOT_FOUND;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.VALIDATED;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import lombok.val;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Account;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.AccountEvent;
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccount;
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountSubType;
import org.cardanofoundation.lob.app.organisation.domain.entity.OpeningBalance;

class AccountEventCodesConversionTaskItemTest {

    private PipelineTaskItem taskItem;

    private OrganisationPublicApiIF organisationPublicApiIF;

    @BeforeEach
    public void setup() {
        this.organisationPublicApiIF = mock(OrganisationPublicApiIF.class);
        this.taskItem = new AccountEventCodesConversionTaskItem(organisationPublicApiIF);
    }

    @Test
    public void testChartOfAccountsMappingFoundForBothDebitAndCredit() {
        val txId = Transaction.id("1", "1");
        val accountDebitRefCode = "DR_REF";
        val accountCreditRefCode = "CR_REF";
        val accountCodeDebit = "1";
        val accountCodeCredit = "2";
        val organisationId = "1";

        ChartOfAccountSubType chartOfAccountSubType = mock(ChartOfAccountSubType.class);
        when(organisationPublicApiIF.getChartOfAccounts(eq(organisationId), eq(accountCodeCredit)))
                .thenReturn(Optional.of(new ChartOfAccount(new ChartOfAccount.Id(organisationId, accountCodeCredit), accountCreditRefCode, "name1", "USD", "couterParty",null, true, chartOfAccountSubType, new OpeningBalance())));

        when(organisationPublicApiIF.getChartOfAccounts(eq(organisationId), eq(accountCodeDebit)))
                .thenReturn(Optional.of(new ChartOfAccount(new ChartOfAccount.Id(organisationId, accountCodeDebit), accountDebitRefCode, "name2", "USD", "couterParty", null,true, chartOfAccountSubType, new OpeningBalance())));

        when(organisationPublicApiIF.findEventCode(eq(organisationId), eq("DR_REF"), eq("CR_REF"))).thenReturn(Optional.of(AccountEvent.builder()
                .name("name")
                .build()));

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        txItem.setAccountDebit(Optional.of(Account.builder()
                .code(accountCodeDebit)
                .build()
        ));

        txItem.setAccountCredit(Optional.of(Account.builder()
                .code(accountCodeCredit)
                .build()
        ));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(Organisation.builder().id(organisationId).build());
        tx.setTransactionType(TransactionType.FxRevaluation);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(VALIDATED);
        assertThat(tx.getViolations()).isEmpty();

        assertThat(tx.getItems().iterator().next().getAccountDebit().map(Account::getRefCode).orElseThrow()).isEqualTo(Optional.of(accountDebitRefCode));
        assertThat(tx.getItems().iterator().next().getAccountCredit().map(Account::getRefCode).orElseThrow()).isEqualTo(Optional.of(accountCreditRefCode));
        assertThat(tx.getItems().iterator().next().getAccountEvent().map(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.AccountEvent::getCode).orElseThrow()).isEqualTo("DR_REFCR_REF");
    }

    @Test
    public void testChartOfAccountsMappingNotFoundForDebit() {
        val txId = Transaction.id("2", "1");
        val accountCodeDebit = "3";
        val organisationId = "1";

        when(organisationPublicApiIF.getChartOfAccounts(organisationId, accountCodeDebit))
                .thenReturn(Optional.empty());

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "1"));

        txItem.setAccountDebit(Optional.of(Account.builder()
                .code(accountCodeDebit)
                .build()
        ));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("2");
        tx.setOrganisation(Organisation.builder().id(organisationId).build());
        tx.setTransactionType(TransactionType.BillCredit);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).hasSize(1);
        assertThat(tx.getViolations().iterator().next().getCode()).isEqualTo(CHART_OF_ACCOUNT_NOT_FOUND);
    }

    @Test
    public void testChartOfAccountsMappingNotFoundForCredit() {
        val txId = Transaction.id("3", "1");
        val accountCodeCredit = "4";
        val organisationId = "1";

        when(organisationPublicApiIF.getChartOfAccounts(organisationId, accountCodeCredit))
                .thenReturn(Optional.empty());

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "2"));

        txItem.setAccountCredit(Optional.of(Account.builder()
                .code(accountCodeCredit)
                .build()
        ));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("3");
        tx.setOrganisation(Organisation.builder().id(organisationId).build());
        tx.setTransactionType(TransactionType.FxRevaluation);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(FAILED);
        assertThat(tx.getViolations()).hasSize(1);
        assertThat(tx.getViolations().iterator().next().getCode()).isEqualTo(CHART_OF_ACCOUNT_NOT_FOUND);
    }

    @Test
    public void testNoDebitOrCreditAccountCodesProvided() {
        val txId = Transaction.id("4", "1");
        val organisationId = "1";

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "3"));

        val tx = new TransactionEntity();
        tx.setId(txId);
        tx.setTransactionInternalNumber("4");
        tx.setOrganisation(Organisation.builder().id(organisationId).build());
        tx.setTransactionType(TransactionType.FxRevaluation);
        tx.setItems(Set.of(txItem));

        taskItem.run(tx);

        assertThat(tx.getViolations()).isEmpty();
        assertThat(tx.getAutomatedValidationStatus()).isEqualTo(VALIDATED);
    }

}
