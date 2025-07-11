package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.NET_OFF_TX;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus.OK;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Optional;

import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Account;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;

@ExtendWith(MockitoExtension.class)
class NetOffCreditDebitTaskItemTest {
    private PipelineTaskItem taskItem;

    @Mock
    private OrganisationPublicApiIF organisationPublicApiIF;


    @BeforeEach
    public void setup() {
        this.taskItem = new NetOffCreditDebitTaskItem(organisationPublicApiIF);
    }

    @Test
    void markTxAsNetOffTest() {
        String txId = Transaction.id("1", "1");
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = new org.cardanofoundation.lob.app.organisation.domain.entity.Organisation();
        org.setId("1");
        org.setDummyAccount("0000000000");
        Mockito.when(organisationPublicApiIF.findByOrganisationId("1")).thenReturn(Optional.of(org));

        TransactionItemEntity txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));
        txItem1.setAmountLcy(BigDecimal.valueOf(0));
        txItem1.setAmountFcy(BigDecimal.valueOf(100));
        txItem1.setAccountDebit(Optional.ofNullable(Account.builder().code("0000000000").build()));
        txItem1.setAccountCredit(Optional.ofNullable(Account.builder().code("Test1").build()));
        txItem1.setStatus(OK);
        ;

        TransactionItemEntity txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(txId, "1"));
        txItem2.setAmountLcy(BigDecimal.valueOf(0));
        txItem2.setAmountFcy(BigDecimal.valueOf(100));
        txItem2.setAccountDebit(Optional.ofNullable(Account.builder().code("Test1").build()));
        txItem2.setAccountCredit(Optional.ofNullable(Account.builder().code("0000000000").build()));
        txItem2.setStatus(OK);


        LinkedHashSet<TransactionItemEntity> txItems = new LinkedHashSet<TransactionItemEntity>();
        txItems.add(txItem1);
        txItems.add(txItem2);

        TransactionEntity tx = new TransactionEntity();
        tx.setTransactionType(TransactionType.Journal);
        org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation txOrg = Organisation.builder().id("1").build();
        tx.setOrganisation(txOrg);


        tx.setId(txId);
        tx.setItems(txItems);
        tx.setTransactionInternalNumber("JOURNAL000");

        taskItem.run(tx);

        assertThat(tx.getViolations()).isNotEmpty();
        assertThat(tx.getViolations()).anyMatch(v -> v.getCode() == NET_OFF_TX);
    }

    @Test
    void notMarkTxAsNetOffTest() {
        String txId = Transaction.id("1", "1");
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = new org.cardanofoundation.lob.app.organisation.domain.entity.Organisation();
        org.setId("1");
        org.setDummyAccount("0000000000");
        Mockito.when(organisationPublicApiIF.findByOrganisationId("1")).thenReturn(Optional.of(org));

        TransactionItemEntity txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(txId, "0"));
        txItem1.setAmountLcy(BigDecimal.valueOf(0));
        txItem1.setAmountFcy(BigDecimal.valueOf(100));
        txItem1.setAccountDebit(Optional.ofNullable(Account.builder().code("0000000000").build()));
        txItem1.setAccountCredit(Optional.ofNullable(Account.builder().code("Test1").build()));
        txItem1.setStatus(OK);
        ;

        TransactionItemEntity txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(txId, "1"));
        txItem2.setAmountLcy(BigDecimal.valueOf(0));
        txItem2.setAmountFcy(BigDecimal.valueOf(100));
        txItem2.setAccountDebit(Optional.ofNullable(Account.builder().code("Test2").build()));
        txItem2.setAccountCredit(Optional.ofNullable(Account.builder().code("0000000000").build()));
        txItem2.setStatus(OK);


        LinkedHashSet<TransactionItemEntity> txItems = new LinkedHashSet<TransactionItemEntity>();
        txItems.add(txItem1);
        txItems.add(txItem2);

        TransactionEntity tx = new TransactionEntity();
        tx.setTransactionType(TransactionType.Journal);
        org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation txOrg = Organisation.builder().id("1").build();
        tx.setOrganisation(txOrg);


        tx.setId(txId);
        tx.setItems(txItems);
        tx.setTransactionInternalNumber("JOURNAL000");

        taskItem.run(tx);

        assertThat(tx.getViolations()).isEmpty();
    }
}
