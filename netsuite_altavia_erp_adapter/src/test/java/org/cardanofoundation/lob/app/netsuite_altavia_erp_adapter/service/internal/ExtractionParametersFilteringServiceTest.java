package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.CardCharge;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.FxRevaluation;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.val;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.*;

class ExtractionParametersFilteringServiceTest {

    private ExtractionParametersFilteringService service;
    private UserExtractionParameters userExtractionParameters;
    private org.cardanofoundation.lob.app.organisation.domain.SystemExtractionParameters systemExtractionParameters;
    private Set<Transaction> transactions;

    @BeforeEach
    void setUp() {
        service = new ExtractionParametersFilteringService();
        userExtractionParameters = createUserExtractionParameters();
        systemExtractionParameters = createSystemExtractionParameters();
        transactions = new HashSet<>();
    }

    @Test
    void testApplyExtractionParameters_AllConditionsMatch() {
        // Setup data
        val accountingPeriod = YearMonth.of(2023, 1);
        val entryDate = LocalDate.of(2023, 1, 2);
        val internalTransactionNumber = "123";

        val transaction = createTransaction("org1", accountingPeriod, entryDate, CardCharge, internalTransactionNumber);
        transactions.add(transaction);

        systemExtractionParameters = systemExtractionParameters.toBuilder()
                .accountPeriodFrom(accountingPeriod.atDay(1))
                .accountPeriodTo(LocalDate.of(2023, 12, 1))
                .build();

        userExtractionParameters = userExtractionParameters.toBuilder()
                .organisationId("org1")
                .from(LocalDate.of(2022, 12, 31))
                .to(LocalDate.of(2023, 12, 31))
                .transactionTypes(Arrays.asList(TransactionType.values()))
                .transactionNumbers(List.of(internalTransactionNumber))
                .build();

        // Execute
        val result = service.applyExtractionParameters(transactions, userExtractionParameters, systemExtractionParameters);

        // Verify
        assertThat(result).containsExactly(transaction);
    }

    @Test
    void testApplyExtractionParameters_NoMatchingOrganisation() {
        val accountingPeriod = YearMonth.of(2023, 1);
        val entryDate = LocalDate.of(2023, 1, 2);
        val internalTransactionNumber = "123";

        val transaction = createTransaction("org2", accountingPeriod, entryDate, FxRevaluation, internalTransactionNumber);

        transactions.add(transaction);

        systemExtractionParameters = systemExtractionParameters.toBuilder()
                .accountPeriodFrom(accountingPeriod.atDay(1))
                .accountPeriodTo(LocalDate.of(2023, 12, 1))
                .build();

        userExtractionParameters = userExtractionParameters.toBuilder()
                .organisationId("org1")
                .from(LocalDate.of(2022, 12, 31))
                .to(LocalDate.of(2023, 12, 31))
                .transactionTypes(List.of(CardCharge))
                .transactionNumbers(List.of(internalTransactionNumber))
                .build();

        // Execute
        val result = service.applyExtractionParameters(transactions, userExtractionParameters, systemExtractionParameters);

        // Verify
        assertThat(result).isEmpty();
    }

    @Test
    void testApplyExtractionParameters_TransactionDateOutOfRange() {
        val accountingPeriod = YearMonth.of(2024, 1);
        val entryDate = LocalDate.of(2024, 1, 2);
        val internalTransactionNumber = "123";

        val transaction = createTransaction("org1", accountingPeriod, entryDate, FxRevaluation, internalTransactionNumber);
        transactions.add(transaction);

        systemExtractionParameters = systemExtractionParameters.toBuilder()
                .accountPeriodFrom(LocalDate.of(2023, 1, 1))
                .accountPeriodTo(LocalDate.of(2023, 12, 1))
                .build();

        userExtractionParameters = userExtractionParameters.toBuilder()
                .organisationId("org1")
                .from(LocalDate.of(2022, 12, 31))
                .to(LocalDate.of(2023, 12, 31))
                .transactionTypes(List.of(CardCharge))
                .transactionNumbers(List.of(internalTransactionNumber))
                .build();

        // Execute
        val result = service.applyExtractionParameters(transactions, userExtractionParameters, systemExtractionParameters);

        // Verify
        assertThat(result).isEmpty();
    }

    @Test
    void testApplyExtractionParameters_EmptyTransactionTypes() {
        val accountingPeriod = YearMonth.of(2023, 1);
        val entryDate = LocalDate.of(2023, 1, 2);
        val internalTransactionNumber = "123";

        val transaction = createTransaction("org1", accountingPeriod, entryDate, FxRevaluation, internalTransactionNumber);
        transactions.add(transaction);

        systemExtractionParameters = systemExtractionParameters.toBuilder()
                .accountPeriodFrom(LocalDate.of(2023, 1, 1))
                .accountPeriodTo(LocalDate.of(2023, 12, 1))
                .build();

        userExtractionParameters = userExtractionParameters.toBuilder()
                .organisationId("org1")
                .from(LocalDate.of(2022, 12, 31))
                .to(LocalDate.of(2023, 12, 31))
                .transactionTypes(List.of(CardCharge))
                .transactionNumbers(List.of("123"))
                .build();

        // Execute
        val result = service.applyExtractionParameters(transactions, userExtractionParameters, systemExtractionParameters);

        // Verify
        assertThat(result).isEmpty();
    }

    private Transaction createTransaction(String orgId,
                                          YearMonth accountingPeriod,
                                          LocalDate entryDate,
                                          TransactionType transactionType,
                                          String internalTransactionNumber) {
        Organisation organisation = Organisation.builder()
                .id(orgId)
                .currencyId("USD")
                .build();

        return Transaction.builder()
                .id(Transaction.id(orgId, internalTransactionNumber))
                .internalTransactionNumber(internalTransactionNumber)
                .batchId("batch1")
                .entryDate(entryDate)
                .transactionType(transactionType)
                .organisation(organisation)
                .accountingPeriod(accountingPeriod)
                .build();
    }

    private UserExtractionParameters createUserExtractionParameters() {
        return UserExtractionParameters.builder()
                .organisationId("defaultOrgId")
                .from(LocalDate.of(2022, 1, 1))
                .to(LocalDate.of(2022, 12, 31))
                .transactionTypes(List.of(CardCharge, FxRevaluation))
                .transactionNumbers(List.of("default123"))
                .build();
    }

    private org.cardanofoundation.lob.app.organisation.domain.SystemExtractionParameters createSystemExtractionParameters() {
        return org.cardanofoundation.lob.app.organisation.domain.SystemExtractionParameters.builder()
                .accountPeriodFrom(LocalDate.of(2022, 1, 1))
                .accountPeriodTo(LocalDate.of(2022, 12, 1))
                .build();
    }

}
