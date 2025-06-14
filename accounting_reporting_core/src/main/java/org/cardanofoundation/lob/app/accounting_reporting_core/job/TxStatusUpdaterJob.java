package org.cardanofoundation.lob.app.accounting_reporting_core.job;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.ReportRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.LedgerService;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.ReportService;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.TransactionBatchService;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.accounting_reporting_core.enabled", havingValue = "true", matchIfMissing = true)
public class TxStatusUpdaterJob {

    private final Map<String, TxStatusUpdate> txStatusUpdatesMap = new ConcurrentHashMap<>();
    private final LedgerService ledgerService;
    private final TransactionBatchService transactionBatchService;
    private final ReportRepository reportRepository;
    private final ReportService reportService;

    @Value("${lob.blockchain.tx-status-updater.max-map-size:1000}")
    private int maxMapSize;

    // This Job collects all TxStatusUpdate events and updates the transactions in the database
    @Scheduled(
            fixedDelayString = "${ob.blockchain.tx-status-updater.fixed_delay:PT30S}",
            initialDelayString = "${lob.blockchain.tx-status-updater.delay:PT30S}")
    @Transactional
    public void execute() {
        Map<String, TxStatusUpdate> updates;
        synchronized (txStatusUpdatesMap) {
            updates = new HashMap<>(txStatusUpdatesMap);
        }
        if(updates.isEmpty()) {
            log.debug("No TxStatusUpdate events to process");
            return;
        }
        try {
            log.info("Updating Status of {} transactions", updates.size());
            List<TransactionEntity> transactionEntities = ledgerService.updateTransactionsWithNewStatuses(updates);
            ledgerService.saveAllTransactionEntities(transactionEntities);

            transactionBatchService.updateBatchesPerTransactions(updates);
            updates.forEach(txStatusUpdatesMap::remove);

            // Updating respective reports - Could be refactored to a separate method
            Set<ReportEntity> reportEntitiesToBeUpdated = new HashSet<>();
            for(TransactionEntity tx : transactionEntities) {
                if (tx.getLedgerDispatchStatus() == LedgerDispatchStatus.FINALIZED) {
                    LocalDate date = tx.getEntryDate();
                    int year = date.getYear();
                    int month = date.getMonthValue();
                    int quarter = (month - 1) / 3 + 1;
                    reportEntitiesToBeUpdated.addAll(reportRepository.findNotPublishedByOrganisationIdAndContainingDate(tx.getOrganisation().getId(), year, quarter, month));
                }
            }

            reportEntitiesToBeUpdated.forEach(report -> {
                log.info("Checking if report {} is ready to publish", report.getId());
                Either<Problem, Boolean> isReadyToPublish = reportService.canPublish(report);
                if (isReadyToPublish.isLeft()) {
                    log.error("Report {} cannot be published: {}", report.getId(), isReadyToPublish.getLeft().getDetail());
                    return;
                }
                report.setIsReadyToPublish(isReadyToPublish.get());
                reportRepository.save(report);
            });

        } catch (Exception e) {
            log.error("Failed to process TxStatusUpdates - entries will be retained in the map", e);
        }
    }

    public void addToStatusUpdateMap(Map<String, TxStatusUpdate> updateMap) {
        synchronized (txStatusUpdatesMap) {
            txStatusUpdatesMap.putAll(updateMap);
        }
        if(txStatusUpdatesMap.size() > maxMapSize) {
            log.warn("TxStatusUpdate map size exceeded the limit of {}. Current size: {}", maxMapSize, txStatusUpdatesMap.size());
        }
    }


}
