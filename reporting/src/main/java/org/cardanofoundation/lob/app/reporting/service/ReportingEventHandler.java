package org.cardanofoundation.lob.app.reporting.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.BlockchainReceipt;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ReportStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.ReportsLedgerUpdatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TxsLedgerUpdatedEvent;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.reporting.job.ReprocessJob;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReportingEventHandler {

    private final ReportingRepository reportingRepository;
    private final ReprocessJob reprocessJob;

    @EventListener
    @Async
    public void handleReportsLedgerUpdated(ReportsLedgerUpdatedEvent event) {
        log.info("Received handleReportsLedgerUpdated, event: {}", event);
        Map<String, ReportStatusUpdate> reportStatusUpdatesMap = event.statusUpdatesMap();

        List<ReportEntity> allReportsToUpdate = reportingRepository.findAllById(reportStatusUpdatesMap.keySet());
        for(ReportEntity reportEntity : allReportsToUpdate) {
            ReportStatusUpdate statusUpdate = reportStatusUpdatesMap.get(reportEntity.getId());

            reportEntity.setLedgerDispatchStatusErrorReason(statusUpdate.getLedgerDispatchStatusErrorReason());
            reportEntity.setLedgerDispatchStatus(statusUpdate.getStatus());
            if (!statusUpdate.getBlockchainReceipts().isEmpty()) {
                BlockchainReceipt firstBlockchainReceipt = statusUpdate.getBlockchainReceipts().stream().iterator().next();
                reportEntity.setBlockchainHash(firstBlockchainReceipt.getHash());
                reportEntity.setBlockchainType(firstBlockchainReceipt.getType());
            }
            reportingRepository.save(reportEntity);
        }
        log.info("Finished processing handleReportsLedgerUpdated, event: {}", event); // 58499305.14,
    }

    // When transaction ledger updates include FINALIZED entries, trigger automatic reprocessing of affected reports.
    @EventListener
    @Async
    public void handleTxsLedgerUpdateEvent(TxsLedgerUpdatedEvent event) {
        Set<TxStatusUpdate> finalizedTransactions = event.getStatusUpdates().stream().filter(statusUpdate -> statusUpdate.getStatus().equals(LedgerDispatchStatus.FINALIZED)).collect(Collectors.toSet());
        if(finalizedTransactions.isEmpty()) {
            log.debug("No finalized transactions found in handleTxsLedgerUpdateEvent, skipping reprocessing.");
            return;
        }
        log.info("Received handleTxsLedgerUpdateEvent, finalized transactions count: {}", finalizedTransactions.size());
        reprocessJob.addAll(finalizedTransactions);
    }
}
