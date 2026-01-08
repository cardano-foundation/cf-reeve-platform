package org.cardanofoundation.lob.app.reporting.job;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.TransactionRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;
import org.cardanofoundation.lob.app.reporting.service.ReportingService;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.reporting_v2.enabled", havingValue = "true", matchIfMissing = true)
public class ReprocessJob {

    private final ReportingService reportingService;
    private final Set<String> finalizedTransactionsUpdates = new HashSet<>();
    private final TransactionRepositoryGateway transactionRepositoryGateway;
    private final ReportingRepository reportingRepository;

    @Scheduled(
            fixedDelayString = "${lob.blockchain.dispatcher.fixed_delay:PT1M}",
            initialDelayString = "${lob.blockchain.dispatcher.initial_delay:PT10S}")
    public void execute() {
        log.debug("Executing TransactionDispatcherJob...");
        Set<String> transactionsToProcess = new HashSet<>(finalizedTransactionsUpdates);
        List<String> allFinalizedTransactions = transactionRepositoryGateway.findByAllId(transactionsToProcess)
                .stream().filter(tx -> tx.getLedgerDispatchStatus().equals(LedgerDispatchStatus.FINALIZED))
                .map(TransactionEntity::getId).toList();
        // Removing since it's processed
        finalizedTransactionsUpdates.removeIf(allFinalizedTransactions::contains);
        reportingRepository.findAffectedByTxId(allFinalizedTransactions).forEach(reportEntity -> reportingService.reprocess(reportEntity.getOrganisationId(), reportEntity.getId()));
        log.debug("Finished executing TransactionDispatcherJob.");
    }

    public void addAll(Set<TxStatusUpdate> txStatusUpdates) {
        finalizedTransactionsUpdates.addAll(txStatusUpdates.stream().map(TxStatusUpdate::getTxId).toList());
    }
}
