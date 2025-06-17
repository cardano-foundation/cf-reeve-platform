package org.cardanofoundation.lob.app.blockchain_publisher.service.event_handle;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.TestPropertySource;

import org.flywaydb.core.Flyway;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.Report;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.ReportLedgerUpdateCommand;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TransactionLedgerUpdateCommand;
import org.cardanofoundation.lob.app.blockchain_publisher.config.JaversConfig;
import org.cardanofoundation.lob.app.blockchain_publisher.config.JpaConfig;
import org.cardanofoundation.lob.app.blockchain_publisher.config.TimeConfig;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.ReportEntityRepository;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.TransactionEntityRepository;
import org.cardanofoundation.lob.app.blockchain_publisher.service.API1L1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.API3L1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch.BlockchainReportsDispatcher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch.BlockchainTransactionsDispatcher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit.BlockchainTransactionSubmissionService;
import org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit.TransactionSubmissionService;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;


@SpringBootTest(classes = {JaversConfig.class, TimeConfig.class, JpaConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
@EnableAutoConfiguration
@ComponentScan(basePackages = {"org.cardanofoundation.lob.app.blockchain_publisher","org.cardanofoundation.lob.app.organisation","org.cardanofoundation.lob.app.blockchain_reader","org.cardanofoundation.lob.app.support.security"})
class BlockchainPublisherServiceDuplicateEventsTest {

    @Autowired
    private BlockchainPublisherEventHandler blockchainPublisherEventHandler;
    @Autowired
    private TransactionEntityRepository transactionEntityRepository;
    @Autowired
    private ReportEntityRepository reportEntityRepository;
    @MockBean
    private BlockchainReportsDispatcher blockchainReportsDispatcher;
    @MockBean
    private BlockchainTransactionsDispatcher blockchainTransactionsDispatcher;
    @MockBean
    private BlockchainTransactionSubmissionService blockchainTransactionSubmissionService;
    @MockBean
    private TransactionSubmissionService transactionSubmissionService;
    @MockBean
    private API1L1TransactionCreator api1L1TransactionCreator;
    @MockBean
    private API3L1TransactionCreator api3L1TransactionCreator;


    @BeforeEach
    public void clearDatabase(@Autowired Flyway flyway){
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void testHandleLedgerUpdateCommand_duplicateEvents() {
        TransactionLedgerUpdateCommand command = TransactionLedgerUpdateCommand.create(EventMetadata.create("test", "test"), "orgId",
                Set.of(Transaction.builder()
                        .id(String.format("%-64s", "txId"))
                                .internalTransactionNumber("internalTxNumber")
                                .entryDate(LocalDate.now())
                                .transactionType(TransactionType.BillCredit)
                                .batchId("batchId")
                                .organisation(Organisation.builder()
                                        .id("orgId")
                                        .name(Optional.of("orgName"))
                                        .countryCode(Optional.of("CH"))
                                        .taxIdNumber(Optional.of("taxIDNumber"))
                                        .currencyId("ISO_4217:CHF")
                                        .build())
                                .accountingPeriod(YearMonth.now())
                .build()));
        blockchainPublisherEventHandler.handleLedgerUpdateCommand(command);
        blockchainPublisherEventHandler.handleLedgerUpdateCommand(command);

        List<TransactionEntity> allEntities = transactionEntityRepository.findAll();
        Assertions.assertEquals(1, allEntities.size());
    }

    @Test
    void testHandleLedgerUpdateCommandReport_duplicateEvents() {
        ReportLedgerUpdateCommand command = ReportLedgerUpdateCommand.create(EventMetadata.create("test", "test"), "orgId",
                Set.of(Report.builder()
                                .reportId("reportId")
                                .idReport("reportId")
                                .organisation(Organisation.builder()
                                        .id("orgId")
                                        .name(Optional.of("orgName"))
                                        .countryCode(Optional.of("CH"))
                                        .taxIdNumber(Optional.of("taxIDNumber"))
                                        .currencyId("ISO_4217:CHF")
                                        .build())
                                .type(ReportType.BALANCE_SHEET)
                                .intervalType(IntervalType.MONTH)
                                .year((short) 2025)
                                .ver(1)
                                .date(LocalDate.now())
                                .build()));

        blockchainPublisherEventHandler.handleLedgerUpdateCommand(command);
        blockchainPublisherEventHandler.handleLedgerUpdateCommand(command);

        List<ReportEntity> all = reportEntityRepository.findAll();
        Assertions.assertEquals(1, all.size());
    }

}
