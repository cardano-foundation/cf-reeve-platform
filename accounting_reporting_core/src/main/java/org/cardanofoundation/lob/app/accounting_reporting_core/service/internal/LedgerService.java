package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;


import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.BlockchainReceipt;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ReportStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.Report;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.LedgerDispatchReceipt;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.ReportLedgerUpdateCommand;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TransactionLedgerUpdateCommand;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.ReportRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.collections.Partitions;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;


@Service
@Slf4j
@RequiredArgsConstructor
public class LedgerService {

    private final AccountingCoreTransactionRepository accountingCoreTransactionRepository;
    private final ReportRepository reportRepository;
    private final ReportConverter reportConverter;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TransactionConverter transactionConverter;
    private final PIIDataFilteringService piiDataFilteringService;
    private final OrganisationPublicApi organisationPublicApi;


    @Value("${ledger.dispatch.batch.size:100}")
    private int dispatchBatchSize;

    @Transactional(readOnly = true)
    public List<TransactionEntity> updateTransactionsWithNewStatuses(Map<String, TxStatusUpdate> statuses) {
        log.info("Updating dispatch status for statusMapCount: {}", statuses.size());

        Set<String> txIds = statuses.keySet();

        List<TransactionEntity> transactionEntities = accountingCoreTransactionRepository.findAllById(txIds);

        for (TransactionEntity tx : transactionEntities) {
            TxStatusUpdate txStatusUpdate = statuses.get(tx.getId());
            tx.setLedgerDispatchStatus(txStatusUpdate.getStatus());
            tx.setLedgerDispatchStatusErrorReason(txStatusUpdate.getLedgerDispatchStatusErrorReason());
            // TODO for now we only support one blockchain but this is open for adding more blockchains
            if (!txStatusUpdate.getBlockchainReceipts().isEmpty()) {
                BlockchainReceipt firstBlockchainReceipt = txStatusUpdate.getBlockchainReceipts().stream().iterator().next();

                String type = firstBlockchainReceipt.getType();
                String hash = firstBlockchainReceipt.getHash();

                tx.setLedgerDispatchReceipt(new LedgerDispatchReceipt(type, hash));
            }
        }
        return transactionEntities;

    }

    @Transactional
    public void saveAllTransactionEntities(Collection<TransactionEntity> transactionEntities) {
        accountingCoreTransactionRepository.saveAll(transactionEntities);
    }

    @Transactional(readOnly = true)
    public List<ReportEntity> updateReportsWithNewStatuses(Map<String, ReportStatusUpdate> reportStatusUpdateMap) {
        log.info("Updating dispatch status for statusMapCount: {}", reportStatusUpdateMap.size());

        Set<String> reportIds = reportStatusUpdateMap.keySet();

        List<ReportEntity> reports = reportRepository.findAllById(reportIds);

        for (ReportEntity report : reports) {
            ReportStatusUpdate reportsLedgerUpdatedEvent = reportStatusUpdateMap.get(report.getId());
            report.setLedgerDispatchStatus(reportsLedgerUpdatedEvent.getStatus());
            report.setLedgerDispatchStatusErrorReason(reportsLedgerUpdatedEvent.getLedgerDispatchStatusErrorReason());

            // TODO for now we only support one blockchain but this is open for adding more blockchains
            if (!reportsLedgerUpdatedEvent.getBlockchainReceipts().isEmpty()) {
                BlockchainReceipt firstBlockchainReceipt = reportsLedgerUpdatedEvent.getBlockchainReceipts().stream().iterator().next();

                String type = firstBlockchainReceipt.getType();
                String hash = firstBlockchainReceipt.getHash();

                report.setLedgerDispatchReceipt(new LedgerDispatchReceipt(type, hash));
            }
        }

        return reports;
    }

    @Transactional
    public void saveAllReports(Collection<ReportEntity> reports) {
        reportRepository.saveAll(reports);
    }

    @Transactional(readOnly = true)
    public void dispatchPending(int limit) {
        for (Organisation organisation : organisationPublicApi.listAll()) {
            Set<TransactionEntity> dispatchTransactions = accountingCoreTransactionRepository.findDispatchableTransactions(organisation.getId(), Limit.of(limit));
            Set<ReportEntity> dispatchReports = reportRepository.findDispatchableReports(organisation.getId(), Limit.of(limit));

            log.info("dispatchPending transactions and reports, organisationId: {}, total tx count: {}", organisation.getId(), dispatchTransactions.size());

            dispatchPendingTransactions(organisation.getId(), dispatchTransactions);
            dispatchReports(organisation.getId(), dispatchReports);
        }
    }

    @Transactional(readOnly = true)
    public void dispatchPendingTransactions(String organisationId,
                                            Set<TransactionEntity> transactions) {
        log.info("dispatchTransactionToBlockchainPublisher, total tx count: {}", transactions.size());

        if (transactions.isEmpty()) {
            log.info("dispatchPendingTransactions, no transactions to dispatch.");
            return;
        }

        Set<Transaction> canonicalTxs = transactionConverter.convertFromDb(transactions);
        Set<Transaction> piiFilteredOutTransactions = piiDataFilteringService.apply(canonicalTxs);

        for (Partitions.Partition<Transaction> partition : Partitions.partition(piiFilteredOutTransactions, dispatchBatchSize)) {
            Set<Transaction> txs = partition.asSet();

            log.info("dispatchTransactionToBlockchainPublisher, txs, partitionSize: {}", txs.size());

            applicationEventPublisher.publishEvent(TransactionLedgerUpdateCommand.create(
                    EventMetadata.create(TransactionLedgerUpdateCommand.VERSION),
                    organisationId,
                    txs)
            );
        }
    }

    public void dispatchReports(String organisationId,
                                Set<ReportEntity> reportEntities) {
        log.info("dispatchReports, total reports count: {}", reportEntities.size());

        if (reportEntities.isEmpty()) {
            log.info("dispatchReports, no reports to dispatch.");
            return;
        }

        Set<Report> canonicalReports = reportConverter.convertFromDbToCanonicalForm(reportEntities);
        for (Partitions.Partition<Report> partition : Partitions.partition(canonicalReports, dispatchBatchSize)) {
            Set<Report> reports = partition.asSet();

            log.info("dispatchTransactionToBlockchainPublisher, reports, reportsCount: {}", reports.size());

            applicationEventPublisher.publishEvent(ReportLedgerUpdateCommand.create(
                    EventMetadata.create(ReportLedgerUpdateCommand.VERSION),
                    organisationId,
                    reports)
            );
        }
    }

}
