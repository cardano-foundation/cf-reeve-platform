package org.cardanofoundation.lob.app.reporting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.ProblemDetail;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Account;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccount;
import org.cardanofoundation.lob.app.reporting.config.TestContainerConfig;
import org.cardanofoundation.lob.app.reporting.dto.ReportGenerateRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportFieldDateRange;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

@SpringBootTest
@ContextConfiguration(classes = ReportingServiceAggregationIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
@Transactional
class ReportingServiceAggregationIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories("org.cardanofoundation.lob")
    @EntityScan("org.cardanofoundation.lob")
    @ComponentScan(basePackages = "org.cardanofoundation.lob")
    @Import({TestContainerConfig.class})
    public static class TestConfig {
        @Bean
        public Clock clock() {
            return Clock.fixed(Instant.parse("2025-02-26T00:00:00.00Z"), ZoneId.of("UTC"));
        }
    }

    @Autowired
    private ReportingService reportingService;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        // Insert ChartOfAccount
        ChartOfAccount chartOfAccount = ChartOfAccount.builder()
                .id(new ChartOfAccount.Id("org123", "4000"))
                .eventRefCode("EVT001")
                .name("Revenue")
                .currencyId("ISO_4217:USD")
                .counterParty("CLIENT")
                .build();
        entityManager.persist(chartOfAccount);

        // Insert ReportTemplateFieldEntity
        ReportTemplateFieldEntity fieldEntity = new ReportTemplateFieldEntity();
        fieldEntity.setName("Revenue");
        fieldEntity.setMappingAccounts(java.util.Set.of(chartOfAccount));
        fieldEntity.setDateRange(ReportFieldDateRange.PERIOD);
        fieldEntity.setChildFields(List.of());

        // Insert ReportTemplateEntity (cascade will persist field)
        ReportTemplateEntity templateEntity = new ReportTemplateEntity();
        templateEntity.setId("abc");
        templateEntity.setOrganisationId("org123");
        templateEntity.setName("Test Template");
        templateEntity.setActive(true);
        templateEntity.setDataMode(DataMode.SYSTEM);
        templateEntity.setFields(List.of(fieldEntity));
        templateEntity.setValidationRules(List.of());
        templateEntity.setReportTemplateType(ReportTemplateType.INCOME_STATEMENT);
        fieldEntity.setReportTemplate(templateEntity);
        entityManager.persist(templateEntity);

        // Insert TransactionBatchEntity
        TransactionBatchEntity batch = new TransactionBatchEntity();
        batch.setId("batch1");
        batch.setFilteringParameters(
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.FilteringParameters.builder()
                        .organisationId("org123")
                        .from(LocalDate.of(2024, 1, 1))
                        .to(LocalDate.of(2024, 1, 31))
                        .build()
        );
        batch.setExtractorType("netsuite");
        batch.setTransactions(new java.util.LinkedHashSet<>());
        entityManager.persist(batch);

        // Insert TransactionEntity
        TransactionEntity transaction = TransactionEntity.builder()
                .id("tx1")
                .transactionType(TransactionType.CustomerInvoice)
                .batchId("batch1")
                .entryDate(LocalDate.of(2024, 1, 15))
                .accountingPeriod(YearMonth.of(2024, 1))
                .internalTransactionNumber("INV001")
                .automatedValidationStatus(TxValidationStatus.VALIDATED)
                .ledgerDispatchStatus(LedgerDispatchStatus.FINALIZED)
                .overallStatus(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionStatus.OK)
                .extractorType("netsuite")
                .build();
        transaction.setOrganisation(
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder()
                        .id("org123")
                        .currencyId("ISO_4217:USD")
                        .build()
        );
        entityManager.persist(transaction);
        batch.getTransactions().add(transaction);
        entityManager.persist(batch);

        // Insert TransactionItemEntity - DEBIT side account 4000
        TransactionItemEntity item1 = new TransactionItemEntity();
        item1.setId("item1");
        item1.setTransaction(transaction);
        item1.setAccountDebit(java.util.Optional.of(Account.builder().code("4000").build()));
        item1.setAccountCredit(java.util.Optional.of(Account.builder().code("5000").build()));
        item1.setAmountLcy(new BigDecimal("1000.00"));
        item1.setAmountFcy(new BigDecimal("1000.00"));
        item1.setFxRate(BigDecimal.ONE);
        item1.setOperationType(OperationType.DEBIT);
        item1.setStatus(TxItemValidationStatus.OK);
        entityManager.persist(item1);

        // Insert TransactionItemEntity - CREDIT side account 4000 (should be negative)
        TransactionItemEntity item2 = new TransactionItemEntity();
        item2.setId("item2");
        item2.setTransaction(transaction);
        item2.setAccountDebit(java.util.Optional.of(Account.builder().code("5000").build()));
        item2.setAccountCredit(java.util.Optional.of(Account.builder().code("4000").build()));
        item2.setAmountLcy(new BigDecimal("200.00"));
        item2.setAmountFcy(new BigDecimal("200.00"));
        item2.setFxRate(BigDecimal.ONE);
        item2.setOperationType(OperationType.CREDIT);
        item2.setStatus(TxItemValidationStatus.OK);
        entityManager.persist(item2);

        entityManager.flush();
    }

    @Test
    void generate_WithRealTransactionData_returnsCorrectAggregatedTotals() {
        // Given
        ReportGenerateRequest request = new ReportGenerateRequest();
        request.setReportTemplateId("abc");
        request.setOrganisationId("org123");
        request.setIntervalType("MONTH");
        request.setYear((short) 2024);
        request.setPeriod((short) 1);
        request.setPreview(false);

        // When
        Either<ProblemDetail, ReportResponseDto> result = reportingService.generate(request);

        // Then
        assertTrue(result.isRight());
        ReportResponseDto report = result.get();
        assertEquals("Test Template - MONTH 2024 Period 1", report.getName());

        // Sign convention ported from original Java getItemAmountFromItem():
        // account on same side as operation type → positive.
        // item1: accountDebit=4000, operation=DEBIT → +1000.00
        // item2: accountCredit=4000, operation=CREDIT → +200.00
        // Total = 1200.00
        assertEquals(1, report.getFields().size());
        assertEquals("Revenue", report.getFields().get(0).getTemplateFieldName());
        assertEquals(0, new BigDecimal("1200.00").compareTo(report.getFields().get(0).getValue()));
    }

    @Test
    void generate_WithPreviewFilter_returnsCorrectAggregatedTotals() {
        // Given
        ReportGenerateRequest request = new ReportGenerateRequest();
        request.setReportTemplateId("abc");
        request.setOrganisationId("org123");
        request.setIntervalType("MONTH");
        request.setYear((short) 2024);
        request.setPeriod((short) 1);
        request.setPreview(true);

        // When
        Either<ProblemDetail, ReportResponseDto> result = reportingService.generate(request);

        // Then
        assertTrue(result.isRight());
        ReportResponseDto report = result.get();
        assertEquals(1, report.getFields().size());
        assertEquals("Revenue", report.getFields().get(0).getTemplateFieldName());
        assertEquals(0, new BigDecimal("1200.00").compareTo(report.getFields().get(0).getValue()));
    }
}
