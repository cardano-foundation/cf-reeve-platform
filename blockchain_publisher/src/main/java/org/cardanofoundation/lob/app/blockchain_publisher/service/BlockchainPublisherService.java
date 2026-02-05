package org.cardanofoundation.lob.app.blockchain_publisher.service;

import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.apache.commons.lang3.tuple.Pair;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.Report;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reportsV2.ReportV2Entity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.ReportEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.TransactionEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.reporting.dto.events.PublishReportEvent;

@Service("blockchain_publisher.blockchainPublisherService")
@RequiredArgsConstructor
@Slf4j
public class BlockchainPublisherService {

    private final TransactionEntityRepositoryGateway transactionEntityRepositoryGateway;
    private final ReportEntityRepositoryGateway reportEntityRepositoryGateway;
    private final LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;
    private final TransactionConverter transactionConverter;
    private final ReportConverter reportConverter;
    private final ReportV2Converter reportV2Converter;

    @Transactional
    public void storeTransactionForDispatchLater(String organisationId,
                                                 Set<Transaction> txs) {
        log.info("dispatchTransactionsToBlockchains..., orgId:{}", organisationId);

        Set<TransactionEntity> txEntities = txs.stream()
                .map(transactionConverter::convertToDbDetached)
                .collect(Collectors.toSet());

        Set<TransactionEntity> storedTransactions = transactionEntityRepositoryGateway.storeOnlyNew(txEntities);

        Set<TransactionEntity> rollbackTransaction = transactionEntityRepositoryGateway.updateErrorRollbackTransactions(txEntities);

        ledgerUpdatedEventPublisher.sendTxLedgerUpdatedEvents(organisationId, storedTransactions);
        ledgerUpdatedEventPublisher.sendTxLedgerUpdatedEvents(organisationId, rollbackTransaction);
    }

    @Transactional
    public void storeReportsForDispatchLater(String organisationId,
                                             Set<Report> reports) {
        log.info("storeReportsForDispatchLater..., orgId:{}", organisationId);

        Set<ReportEntity> storedReports = reportEntityRepositoryGateway.storeOnlyNew(reports.stream()
                .map(reportConverter::convertToDbDetached)
                .collect(Collectors.toSet()));
        Set<Pair<String, L1SubmissionData>> reportSet = storedReports.stream().filter(r -> r.getL1SubmissionData().isPresent()).map(r -> Pair.of(r.getId(), r.getL1SubmissionData().get())).collect(Collectors.toSet());
        ledgerUpdatedEventPublisher.sendReportLedgerUpdatedEvents(organisationId, reportSet);
    }

    public void storeReportsForDispatchLater(PublishReportEvent event) {
        ReportV2Entity reportV2Entity = reportV2Converter.convertToDbDetached(event);
        reportEntityRepositoryGateway.storeReportV2IfNew(reportV2Entity);
    }

}
