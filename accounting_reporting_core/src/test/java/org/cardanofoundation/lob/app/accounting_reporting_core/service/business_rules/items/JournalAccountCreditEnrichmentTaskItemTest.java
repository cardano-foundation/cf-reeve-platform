package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.FxRevaluation;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.Journal;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Account;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@ExtendWith(MockitoExtension.class)
class JournalAccountCreditEnrichmentTaskItemTest {

    private static final String DUMMY_ACCOUNT = "0000000000";

    @Mock
    private OrganisationPublicApiIF organisationPublicApiIF;
    @Mock
    private Organisation organisation;

    @InjectMocks
    private JournalAccountCreditEnrichmentTaskItem taskItem;

    private TransactionEntity transaction;

    @Test
    void should_Not_Run_Because_It_Is_Not_A_Journal_Transaction() {
        String organisationId = "org1";

        LinkedHashSet<TransactionItemEntity> items = new LinkedHashSet<TransactionItemEntity>();
        transaction = new TransactionEntity();
        transaction.setOrganisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder()
                .id(organisationId)
                .build()
        );
        transaction.setTransactionType(FxRevaluation);

        TransactionItemEntity txItem1 = new TransactionItemEntity();
        txItem1.setId("1");
        txItem1.clearAccountCodeCredit();
        items.add(txItem1);

        transaction.setItems(items);

        taskItem.run(transaction);

        // check that account credit was not manipulated
        assertThat(txItem1.getAccountCredit()).isEmpty();
    }

    @Test
    void should_Not_Run_Because_Dummy_Account_Is_Missing() {
        String organisationId = "org1";

        LinkedHashSet<TransactionItemEntity> items = new LinkedHashSet<TransactionItemEntity>();
        transaction = new TransactionEntity();
        transaction.setOrganisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder()
                .id(organisationId)
                .build()
        );
        transaction.setTransactionType(Journal);

        TransactionItemEntity txItem1 = new TransactionItemEntity();
        txItem1.setId("1");
        txItem1.clearAccountCodeCredit();
        items.add(txItem1);

        transaction.setItems(items);

        when(organisationPublicApiIF.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));
        when(organisation.getDummyAccount()).thenReturn(Optional.empty());

        taskItem.run(transaction);

        // check that account credit was not manipulated
        assertThat(txItem1.getAccountCredit()).isEmpty();
    }

    @Test
    void should_Not_Run_Because_Not_All_Credit_Accounts_Are_Missing() {
        String organisationId = "org1";

        LinkedHashSet<TransactionItemEntity> items = new LinkedHashSet<TransactionItemEntity>();
        transaction = new TransactionEntity();
        transaction.setOrganisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder()
                .id(organisationId)
                .build()
        );
        transaction.setTransactionType(Journal);

        TransactionItemEntity txItem1 = new TransactionItemEntity();
        txItem1.setId("1");
        txItem1.clearAccountCodeCredit();
        items.add(txItem1);

        TransactionItemEntity txItem2 = new TransactionItemEntity();
        txItem2.setId("2");
        txItem2.setAccountCredit(Optional.of(Account.builder()
                .code("1234567890")
                .build())
        );
        items.add(txItem2);

        transaction.setItems(items);

        when(organisationPublicApiIF.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));
        when(organisation.getDummyAccount()).thenReturn(Optional.empty());

        taskItem.run(transaction);

        // check that account credit was not manipulated
        assertThat(txItem1.getAccountCredit()).isEmpty();
        assertThat(txItem2.getAccountCredit()).isPresent();
    }

    @Test
    void should_Set_Credit_From_Debit_If_Conditions_Met() {
        LinkedHashSet<TransactionItemEntity> items = new LinkedHashSet<>();
        transaction = new TransactionEntity();
        transaction.setOrganisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder()
                .id("org1")
                .build()
        );
        transaction.setTransactionType(Journal);

        TransactionItemEntity item1 = new TransactionItemEntity();
        item1.setId("1");

        item1.setAccountDebit(Optional.of(Account.builder()
                .code("4102110100")
                .build())
        );

        item1.setAmountLcy(BigDecimal.valueOf(988.86));
        item1.setOperationType(OperationType.DEBIT);
        items.add(item1);

        TransactionItemEntity item2 = new TransactionItemEntity();
        item2.setId("2");
        item2.setAccountDebit(Optional.of(Account.builder()
                        .code("4102120100")
                        .build()
                )
        );


        item2.setAmountLcy(BigDecimal.valueOf(188.50));
        item2.setOperationType(OperationType.CREDIT);
        items.add(item2);

        TransactionItemEntity item3 = new TransactionItemEntity();
        item3.setId("3");
        item3.setAccountDebit(Optional.of(Account.builder()
                .code("4102140100")
                .build())
        );

        item3.setAmountLcy(BigDecimal.valueOf(148.64));
        item3.setOperationType(OperationType.DEBIT);
        items.add(item3);

        TransactionItemEntity item4 = new TransactionItemEntity();
        item4.setId("4");

        item4.setAccountDebit(Optional.of(Account.builder()
                .code("5205140100")
                .build())
        );

        item4.setAmountLcy(BigDecimal.valueOf(949.00));
        item4.setOperationType(OperationType.CREDIT);
        items.add(item4);

        TransactionItemEntity item5 = new TransactionItemEntity();
        item5.setId("5");

        item5.setAccountDebit(Optional.of(Account.builder()
                .code("5208110100")
                .build())
        );

        item5.setAmountLcy(BigDecimal.valueOf(528.5));
        item5.setOperationType(OperationType.CREDIT);
        items.add(item5);

        TransactionItemEntity item6 = new TransactionItemEntity();
        item6.setId("6");

        item6.setAccountDebit(Optional.of(Account.builder()
                .code("5208120100")
                .build())

        );
        item6.setAmountLcy(BigDecimal.valueOf(147.30));
        item6.setOperationType(OperationType.CREDIT);
        items.add(item6);

        TransactionItemEntity item7 = new TransactionItemEntity();
        item7.setId("7");

        item7.setAccountDebit(Optional.of(Account.builder()
                .code("5205140100")
                .build())
        );

        item7.setAmountLcy(BigDecimal.valueOf(675.80));
        item7.setOperationType(OperationType.DEBIT);
        items.add(item7);

        TransactionItemEntity item8 = new TransactionItemEntity();
        item8.setId("8");

        item8.setAccountDebit(Optional.of(Account.builder()
                .code("1203210100")
                .build()
        ));

        item8.setAmountLcy(BigDecimal.valueOf(925.40));
        item8.setOperationType(OperationType.CREDIT);
        items.add(item8);

        TransactionItemEntity item9 = new TransactionItemEntity();
        item9.setId("9");

        item9.setAccountDebit(Optional.of(Account.builder()
                .code("5205140100")
                .build()
        ));

        item9.setAmountLcy(BigDecimal.valueOf(925.40));
        item9.setOperationType(OperationType.DEBIT);
        items.add(item9);

        TransactionItemEntity item10 = new TransactionItemEntity();
        item10.setId("10");

        item10.setAccountDebit(Optional.of(Account.builder()
                .code("5205140101")
                .build())
        );

        item10.setAmountLcy(BigDecimal.valueOf(0));
        items.add(item10);

        transaction.setItems(items);

        when(organisationPublicApiIF.findByOrganisationId("org1")).thenReturn(Optional.of(organisation));
        when(organisation.getDummyAccount()).thenReturn(Optional.of(DUMMY_ACCOUNT));

        taskItem.run(transaction);

        assertThat(item1.getAccountDebit().map(Account::getCode).orElseThrow()).isEqualTo("4102110100");
        assertThat(item2.getAccountDebit().map(Account::getCode).orElseThrow()).isEqualTo("0000000000");
        assertThat(item3.getAccountDebit().map(Account::getCode).orElseThrow()).isEqualTo("4102140100");
        assertThat(item4.getAccountDebit().map(Account::getCode).orElseThrow()).isEqualTo("0000000000");
        assertThat(item5.getAccountDebit().map(Account::getCode).orElseThrow()).isEqualTo("0000000000");
        assertThat(item6.getAccountDebit().map(Account::getCode).orElseThrow()).isEqualTo("0000000000");
        assertThat(item7.getAccountDebit().map(Account::getCode).orElseThrow()).isEqualTo("5205140100");
        assertThat(item8.getAccountDebit().map(Account::getCode).orElseThrow()).isEqualTo("0000000000");
        assertThat(item9.getAccountDebit().map(Account::getCode).orElseThrow()).isEqualTo("5205140100");
        assertThat(item10.getAccountDebit().map(Account::getCode).orElseThrow()).isEqualTo("5205140101");

        assertThat(item1.getAccountCredit().map(Account::getCode).orElseThrow()).isEqualTo("0000000000");
        assertThat(item2.getAccountCredit().map(Account::getCode).orElseThrow()).isEqualTo("4102120100");
        assertThat(item3.getAccountCredit().map(Account::getCode).orElseThrow()).isEqualTo("0000000000");
        assertThat(item4.getAccountCredit().map(Account::getCode).orElseThrow()).isEqualTo("5205140100");
        assertThat(item5.getAccountCredit().map(Account::getCode).orElseThrow()).isEqualTo("5208110100");
        assertThat(item6.getAccountCredit().map(Account::getCode).orElseThrow()).isEqualTo("5208120100");
        assertThat(item7.getAccountCredit().map(Account::getCode).orElseThrow()).isEqualTo("0000000000");
        assertThat(item8.getAccountCredit().map(Account::getCode).orElseThrow()).isEqualTo("1203210100");
        assertThat(item9.getAccountCredit().map(Account::getCode).orElseThrow()).isEqualTo("0000000000");
        assertThat(item10.getAccountCredit().map(Account::getCode).orElseThrow()).isEqualTo("0000000000");
    }

    @Test
    void shouldAddERPViolation() {
        LinkedHashSet<TransactionItemEntity> items = new LinkedHashSet<>();
        transaction = new TransactionEntity();
        transaction.setOrganisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder()
                .id("org1")
                .build()
        );
        transaction.setTransactionType(Journal);

        TransactionItemEntity item1 = new TransactionItemEntity();
        item1.setOperationType(OperationType.CREDIT);
        item1.setId("1");
        item1.clearAccountCodeCredit();
        item1.clearAccountCodeDebit();

        items.add(item1);
        transaction.setItems(items);

        when(organisationPublicApiIF.findByOrganisationId("org1")).thenReturn(Optional.of(organisation));
        when(organisation.getDummyAccount()).thenReturn(Optional.of(DUMMY_ACCOUNT));

        taskItem.run(transaction);

        assertThat(transaction.getViolations()).isNotEmpty();
        Set<TransactionViolation> violations = transaction.getViolations();
        assertThat(violations).hasSize(1);
        TransactionViolation violation = violations.iterator().next();
        assertThat(violation.getCode()).isEqualTo(TransactionViolationCode.ACCOUNT_CODE_DEBIT_IS_EMPTY);
        assertThat(violation.getSource()).isEqualTo(Source.ERP);
    }

}
