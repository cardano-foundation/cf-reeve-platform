package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.TestPropertySource;

import org.flywaydb.core.Flyway;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.config.JaversConfig;
import org.cardanofoundation.lob.app.accounting_reporting_core.config.JpaConfig;
import org.cardanofoundation.lob.app.accounting_reporting_core.config.TimeConfig;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ReportStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.SystemExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionBatchStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportMode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.FilteringParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchStartedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.ReportsLedgerUpdatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TxsLedgerUpdatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationStartedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.job.TxStatusUpdaterJob;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.ReportRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionReconcilationRepository;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@SpringBootTest(classes = {JaversConfig.class, TimeConfig.class, JpaConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
@EnableAutoConfiguration
@ComponentScan(basePackages = {"org.cardanofoundation.lob.app.accounting_reporting_core","org.cardanofoundation.lob.app.organisation","org.cardanofoundation.lob.app.blockchain_reader","org.cardanofoundation.lob.app.support.security", "org.cardanofoundation.lob.app.accounting_reporting_core.job"})
class AccountingCoreEventHandlerDuplicateEventsTest {

    @Autowired
    private AccountingCoreEventHandler accountingCoreEventHandler;
    @Autowired
    private AccountingCoreTransactionRepository accountingCoreTransactionRepository;
    @Autowired
    private TransactionBatchRepository transactionBatchRepository;
    @Autowired
    private ReportRepository reportRepository;
    @Autowired
    private TxStatusUpdaterJob txStatusUpdaterJob;
    @Autowired
    private TransactionReconcilationRepository transactionReconcilationRepository;

    @BeforeEach
    void clearDatabase(@Autowired Flyway flyway){
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void testHandleLedgerUpdate() {
        TransactionBatchEntity transactionBatchEntity = new TransactionBatchEntity();
        transactionBatchEntity.setId("batchId");
        transactionBatchEntity.setFilteringParameters(FilteringParameters.builder()
                        .organisationId("testOrg")
                        .from(LocalDate.now().minusWeeks(1))
                        .to(LocalDate.now().plusWeeks(1))
                        .accountingPeriodFrom(LocalDate.now().minusYears(1))
                        .accountingPeriodTo(LocalDate.now().plusYears(1))
                .build());
        transactionBatchRepository.save(transactionBatchEntity);

        TransactionEntity transactionEntity = new TransactionEntity();
        transactionEntity.setId("txId");
        transactionEntity.setOrganisation(Organisation.builder().id("testOrg").build());
        transactionEntity.setTransactionInternalNumber("txInternalNumber");
        transactionEntity.setBatchId("batchId");
        transactionEntity.setAccountingPeriod(YearMonth.now());
        transactionEntity.setEntryDate(LocalDate.now());
        transactionEntity.setTransactionType(TransactionType.BillCredit);
        transactionEntity.setLedgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED);
        accountingCoreTransactionRepository.saveAndFlush(transactionEntity);

        TxsLedgerUpdatedEvent build = TxsLedgerUpdatedEvent.builder()
                .metadata(EventMetadata.create("1.0", "testUser"))
                .organisationId("testOrg")
                // padding the transaction id to 64 characters to match the length of the id in the database
                .statusUpdates(Set.of(new TxStatusUpdate(String.format("%-64s", transactionEntity.getId()), LedgerDispatchStatus.MARK_DISPATCH, Set.of())))
                .build();

        // sending events twice to check if the status is updated correctly and no exceptions are thrown
        accountingCoreEventHandler.handleLedgerUpdatedEvent(build);
        accountingCoreEventHandler.handleLedgerUpdatedEvent(build);
        txStatusUpdaterJob.execute();

        Optional<TransactionEntity> txEntity = accountingCoreTransactionRepository.findById("txId");
        Assertions.assertTrue(txEntity.isPresent());
        Assertions.assertEquals(LedgerDispatchStatus.MARK_DISPATCH, txEntity.get().getLedgerDispatchStatus());
    }

    @Test
    void testHandleReportLedgerUpdate() {

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setReportId("reportId");
        reportEntity.setIdControl("idControl");
        reportEntity.setOrganisation(Organisation.builder().id("testOrg").name("testOrg").countryCode("CH").currencyId("ISO_4217:CHF").taxIdNumber("taxIdNumber").build());
        reportEntity.setType(ReportType.INCOME_STATEMENT);
        reportEntity.setIntervalType(IntervalType.MONTH);
        reportEntity.setYear((short)2021);
        reportEntity.setDate(LocalDate.now());
        reportEntity.setMode(ReportMode.SYSTEM);
        reportEntity.setLedgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED);
        reportRepository.saveAndFlush(reportEntity);

        ReportsLedgerUpdatedEvent reportsLedgerUpdatedEvent = ReportsLedgerUpdatedEvent.builder()
                .metadata(EventMetadata.create("1.0", "testUser"))
                .organisationId("testOrg")
                // padding the transaction id to 64 characters to match the length of the id in the database
                .statusUpdates(Set.of(new ReportStatusUpdate(String.format("%-64s", reportEntity.getReportId()), LedgerDispatchStatus.MARK_DISPATCH, Set.of())))
                .build();

        // sending events twice to check if the status is updated correctly and no exceptions are thrown
        accountingCoreEventHandler.handleReportsLedgerUpdated(reportsLedgerUpdatedEvent);
        accountingCoreEventHandler.handleReportsLedgerUpdated(reportsLedgerUpdatedEvent);

        Optional<ReportEntity> report = reportRepository.findById("reportId");
        Assertions.assertTrue(report.isPresent());
        Assertions.assertEquals(LedgerDispatchStatus.MARK_DISPATCH, report.get().getLedgerDispatchStatus());
    }

    @Test
    void testHandleTransactionBatchFailedEvent() {
        TransactionBatchEntity transactionBatchEntity = new TransactionBatchEntity();
        transactionBatchEntity.setId("batchId");
        transactionBatchEntity.setStatus(TransactionBatchStatus.CREATED);
        transactionBatchEntity.setFilteringParameters(FilteringParameters.builder()
                .organisationId("testOrg")
                .from(LocalDate.now().minusWeeks(1))
                .to(LocalDate.now().plusWeeks(1))
                .accountingPeriodFrom(LocalDate.now().minusYears(1))
                .accountingPeriodTo(LocalDate.now().plusYears(1))
                .build());
        transactionBatchRepository.saveAndFlush(transactionBatchEntity);
        TransactionBatchFailedEvent event = TransactionBatchFailedEvent.builder()
                .batchId("batchId")
                .organisationId("testOrg")
                .error(new FatalError(FatalError.Code.ADAPTER_ERROR, "subCode", Map.of()))
                .build();

        // sending events twice to check if the status is updated correctly and no exceptions are thrown
        accountingCoreEventHandler.handleTransactionBatchFailedEvent(event);
        accountingCoreEventHandler.handleTransactionBatchFailedEvent(event);

        Optional<TransactionBatchEntity> batchId = transactionBatchRepository.findById("batchId");
        Assertions.assertTrue(batchId.isPresent());
        Assertions.assertEquals(TransactionBatchStatus.FAILED, batchId.get().getStatus());
    }

    @Test
    void testHandleTransactionBatchStartedEvent() {
        TransactionBatchStartedEvent event = TransactionBatchStartedEvent.builder()
                .metadata(EventMetadata.create("1.0", "testUser"))
                .batchId("batchId")
                .organisationId("testOrg")
                .userExtractionParameters(UserExtractionParameters.builder()
                        .organisationId("testOrg")
                        .transactionTypes(List.of(TransactionType.CardCharge))
                        .transactionNumbers(List.of())
                        .from(LocalDate.now().minusWeeks(1))
                        .to(LocalDate.now().plusWeeks(1))
                        .build())
                .systemExtractionParameters(SystemExtractionParameters.builder()
                        .organisationId("testOrg")
                        .accountPeriodFrom(LocalDate.now().minusYears(1))
                        .accountPeriodTo(LocalDate.now().plusYears(1))
                        .build())
                .build();

        // sending events twice to check if the status is updated correctly and no exceptions are thrown
        accountingCoreEventHandler.handleTransactionBatchStartedEvent(event);
        accountingCoreEventHandler.handleTransactionBatchStartedEvent(event);

        Optional<TransactionBatchEntity> batchId = transactionBatchRepository.findById("batchId");
        Assertions.assertTrue(batchId.isPresent());
        Assertions.assertEquals(TransactionBatchStatus.CREATED, batchId.get().getStatus());
    }

    @Test
    void testHandleReconcilationChunkFailedEvent() {
        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        reconcilationEntity.setId("reconciliationId");
        reconcilationEntity.setOrganisationId("testOrg");
        reconcilationEntity.setStatus(ReconcilationStatus.CREATED);
        reconcilationEntity.setProcessedTxCount(0);
        reconcilationEntity.setViolations(Set.of());
        transactionReconcilationRepository.saveAndFlush(reconcilationEntity);


        ReconcilationFailedEvent event = ReconcilationFailedEvent.builder()
                .reconciliationId("reconciliationId")
                .organisationId("testOrg")
                .error(new FatalError(FatalError.Code.ADAPTER_ERROR, "subCode", Map.of()))
                .build();

        accountingCoreEventHandler.handleReconcilationChunkFailedEvent(event);
        accountingCoreEventHandler.handleReconcilationChunkFailedEvent(event);

        Optional<ReconcilationEntity> entity = transactionReconcilationRepository.findById("reconciliationId");
        Assertions.assertTrue(entity.isPresent());
        Assertions.assertEquals(ReconcilationStatus.FAILED, entity.get().getStatus());

    }

    @Test
    void testHandleReconcilationStartedEvent() {
        ReconcilationStartedEvent event = ReconcilationStartedEvent.builder()
                .reconciliationId("reconciliationId")
                .to(LocalDate.now().plusWeeks(1))
                .from(LocalDate.now().minusWeeks(1))
                .organisationId("testOrg")
                .build();

        // sending events twice to check if the status is updated correctly and no exceptions are thrown
        accountingCoreEventHandler.handleReconcilationStartedEvent(event);
        accountingCoreEventHandler.handleReconcilationStartedEvent(event);

        Optional<ReconcilationEntity> entity = transactionReconcilationRepository.findById("reconciliationId");
        Assertions.assertTrue(entity.isPresent());
        Assertions.assertEquals(ReconcilationStatus.CREATED, entity.get().getStatus());
    }

}
