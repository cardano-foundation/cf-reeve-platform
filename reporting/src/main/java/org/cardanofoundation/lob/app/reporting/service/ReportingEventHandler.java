package org.cardanofoundation.lob.app.reporting.service;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.BlockchainReceipt;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ReportStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.ReportsLedgerUpdatedEvent;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReportingEventHandler {

    private final ReportingRepository reportingRepository;

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
        log.info("Finished processing handleReportsLedgerUpdated, event: {}", event);
    }
}
