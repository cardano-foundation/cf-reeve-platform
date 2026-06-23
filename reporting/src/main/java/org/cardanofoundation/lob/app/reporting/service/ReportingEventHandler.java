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

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
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
    public void handleLedgerUpdated(LedgerUpdatedEvent event) {
        if (event.getType() == LedgerUpdateType.REPORT) {
            updateReports(event);
        } else if (event.getType() == LedgerUpdateType.TRANSACTION) {
            reprocessFinalizedTransactions(event);
        }
    }

    private void updateReports(LedgerUpdatedEvent event) {
        log.info("Received report ledger update, event: {}", event);
        Map<String, LedgerStatusUpdate> reportStatusUpdatesMap = event.statusUpdatesMap();

        List<ReportEntity> allReportsToUpdate = reportingRepository.findAllById(reportStatusUpdatesMap.keySet());
        for (ReportEntity reportEntity : allReportsToUpdate) {
            LedgerStatusUpdate statusUpdate = reportStatusUpdatesMap.get(reportEntity.getId());

            reportEntity.setLedgerDispatchStatusErrorReason(statusUpdate.getLedgerDispatchStatusErrorReason());
            reportEntity.setLedgerDispatchStatus(statusUpdate.getStatus());
            if (!statusUpdate.getBlockchainReceipts().isEmpty()) {
                BlockchainReceipt firstBlockchainReceipt = statusUpdate.getBlockchainReceipts().iterator().next();
                reportEntity.setBlockchainHash(firstBlockchainReceipt.getHash());
                reportEntity.setBlockchainType(firstBlockchainReceipt.getType());
            }
            reportingRepository.save(reportEntity);
        }
        log.info("Finished processing report ledger update, event: {}", event);
    }

    // When transaction ledger updates include FINALIZED entries, trigger automatic reprocessing of affected reports.
    private void reprocessFinalizedTransactions(LedgerUpdatedEvent event) {
        Set<TxStatusUpdate> finalizedTransactions = event.getStatusUpdates().stream()
                .filter(statusUpdate -> statusUpdate.getStatus() == LedgerDispatchStatus.FINALIZED)
                .map(ReportingEventHandler::toTxStatusUpdate)
                .collect(Collectors.toSet());
        if (finalizedTransactions.isEmpty()) {
            log.debug("No finalized transactions found in ledger update, skipping reprocessing.");
            return;
        }
        log.info("Received finalized transactions, count: {}", finalizedTransactions.size());
        reprocessJob.addAll(finalizedTransactions);
    }

    private static TxStatusUpdate toTxStatusUpdate(LedgerStatusUpdate update) {
        Set<org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.BlockchainReceipt> receipts =
                update.getBlockchainReceipts().stream()
                        .map(receipt -> new org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.BlockchainReceipt(
                                receipt.getType(), receipt.getHash()))
                        .collect(Collectors.toSet());

        return new TxStatusUpdate(update.getId(), update.getStatus(), update.getLedgerDispatchStatusErrorReason(), receipts);
    }
}
