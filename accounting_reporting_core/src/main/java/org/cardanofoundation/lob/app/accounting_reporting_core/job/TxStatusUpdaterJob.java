package org.cardanofoundation.lob.app.accounting_reporting_core.job;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.LedgerService;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.TransactionBatchService;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.accounting_reporting_core.enabled", havingValue = "true", matchIfMissing = true)
public class TxStatusUpdaterJob {

    private final Map<String, TxStatusUpdate> txStatusUpdatesMap = new ConcurrentHashMap<>();
    private final LedgerService ledgerService;
    private final TransactionBatchService transactionBatchService;

    @Value("${lob.blockchain.tx-status-updater.max-map-size:1000}")
    private int maxMapSize;

    // This Job collects all TxStatusUpdate events and updates the transactions in the database
    @Scheduled(
            fixedDelayString = "${ob.blockchain.tx-status-updater.fixed_delay:PT20S}",
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
