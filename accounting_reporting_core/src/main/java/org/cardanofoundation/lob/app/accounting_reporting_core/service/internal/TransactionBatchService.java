package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionBatchStatus.*;
import static org.springframework.transaction.annotation.Propagation.SUPPORTS;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.SystemExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.BatchStatistics;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Details;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.FilteringParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchAssocEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchAssocRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepositoryGateway;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchStatisticsView;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;
import org.cardanofoundation.lob.app.support.reactive.DebouncerManager;
import org.cardanofoundation.lob.app.support.spring_audit.internal.AuditorContext;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionBatchService {

    private final TransactionBatchRepositoryGateway transactionBatchRepositoryGateway;
    private final TransactionBatchRepository transactionBatchRepository;
    private final TransactionConverter transactionConverter;
    private final TransactionBatchAssocRepository transactionBatchAssocRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TxBatchStatusCalculator txBatchStatusCalculator;
    private final DebouncerManager debouncerManager;
    private final AccountingCoreTransactionRepository accountingCoreTransactionRepository;

    @Value("${batch.stats.debounce.duration:PT5S}")
    private Duration batchStatsDebounceDuration;

    public Optional<TransactionBatchEntity> findById(String batchId) {
        return transactionBatchRepository.findById(batchId);
    }

    @Transactional
    public void createTransactionBatch(String batchId,
                                       String organisationId,
                                       UserExtractionParameters userExtractionParameters,
                                       SystemExtractionParameters systemExtractionParameters, String user) {
        log.info("Creating transaction batch, batchId: {}, filteringParameters: {}", batchId, userExtractionParameters);

        if (transactionBatchRepository.findById(batchId).isPresent()) {
            log.info("Transaction batch already exists skipping processing, batchId: {}", batchId);
            return;
        }

        FilteringParameters filteringParameters = transactionConverter.convertToDbDetached(systemExtractionParameters, userExtractionParameters);

        TransactionBatchEntity transactionBatchEntity = new TransactionBatchEntity();
        transactionBatchEntity.setId(batchId);
        transactionBatchEntity.setTransactions(Set.of());
        transactionBatchEntity.setFilteringParameters(filteringParameters);
        transactionBatchEntity.setStatus(CREATED);
        transactionBatchEntity.setCreatedBy(user);
        AuditorContext.setCurrentUser(user);

        transactionBatchRepository.saveAndFlush(transactionBatchEntity);

        log.info("Transaction batch created, batchId: {}", batchId);

        applicationEventPublisher.publishEvent(TransactionBatchCreatedEvent.builder()
                .metadata(EventMetadata.create(TransactionBatchCreatedEvent.VERSION))
                .batchId(batchId)
                .organisationId(organisationId)
                .userExtractionParameters(userExtractionParameters)
                .systemExtractionParameters(systemExtractionParameters)
                .build()
        );
    }

    @Transactional
    public void updateTransactionBatchStatusAndStats(String batchId,
                                                     @Nullable Integer totalTransactionsCount,
                                                     Optional<Set<TransactionEntity>> entities) {
        debouncerManager.callInNewDebouncer(batchId, () -> invokeUpdateTransactionBatchStatusAndStats(batchId, Optional.ofNullable(totalTransactionsCount), entities), batchStatsDebounceDuration);
    }

    @Transactional(propagation = SUPPORTS)
    public void failTransactionBatch(String batchId,
                                     UserExtractionParameters userExtractionParameters,
                                     Optional<SystemExtractionParameters> systemExtractionParameters,
                                     FatalError error) {
        Optional<TransactionBatchEntity> txBatchM = transactionBatchRepositoryGateway.findById(batchId);

        TransactionBatchEntity txBatch = new TransactionBatchEntity();
        if (txBatchM.isPresent()) {
            txBatch = txBatchM.orElseThrow();
        } else {
            FilteringParameters filteringParameters = transactionConverter.convertToDbDetached(userExtractionParameters, systemExtractionParameters);
            txBatch.setId(batchId);
            txBatch.setFilteringParameters(filteringParameters);
        }

        txBatch.setStatus(FAILED);
        txBatch.setDetails(Details.builder()
                .code(error.getCode().name())
                .subCode(error.getSubCode())
                .bag(error.getBag())
                .build()
        );

        transactionBatchRepository.save(txBatch);

        log.info("Transaction batch status updated, batchId: {}", batchId);
    }

    public void invokeUpdateTransactionBatchStatusAndStats(String batchId,
                                                            Optional<Integer> totalTransactionsCountO,
                                                            Optional<Set<TransactionEntity>> transactionEntities) {
        log.info("EXPENSIVE::Updating transaction batch status and statistics, batchId: {}", batchId);

        Optional<TransactionBatchEntity> txBatchM = transactionBatchRepositoryGateway.findById(batchId);

        if (txBatchM.isEmpty()) {
            log.warn("Transaction batch not found for id: {}", batchId);
            return;
        }

        TransactionBatchEntity txBatch = txBatchM.orElseThrow();

        BatchStatisticsView batchStatisticsView = new BatchStatisticsView();
        // Triggering to update the status of all changed transactions within this batch, to be sure to have the latest status
        transactionEntities.ifPresent(entities -> {
            entities.forEach(TransactionEntity::updateProcessingStatus);
            accountingCoreTransactionRepository.saveAll(entities);
        });

        transactionBatchRepository.getBatchStatisticViewForBatchId(batchId).ifPresent(batchStatisticsView::merge);

        if (txBatch.getStatus() == FINALIZED) {
            log.warn("Transaction batch already finalized or failed, batchId: {}", batchId);
            return;
        }
        int totalTransactionsCount = totalTransactionsCountO.orElse(batchStatisticsView.getTotal());
        txBatch.setBatchStatistics(batchStatisticsView.toBatchStatistics(totalTransactionsCount));
        txBatch.setStatus(txBatchStatusCalculator.reCalcStatus(batchStatisticsView, totalTransactionsCount));

        // If the status is Finalized, this means we processed all transactions from ERP
        // In case we dropped duplicate transactions, we will normalize the total transaction count
        if(txBatch.getStatus() == FINALIZED) {
            Optional<BatchStatistics> statistics = txBatch.getBatchStatistics();
            statistics.ifPresent(batchStatistics -> {
                int total = batchStatistics.getInvalidTransactions()
                        + batchStatistics.getApprovedTransactions()
                        + batchStatistics.getPendingTransactions()
                        + batchStatistics.getReadyToApproveTransactions();
                batchStatistics.setTotal(total);
                batchStatistics.setProcessedTransactions(total);
                txBatch.setBatchStatistics(batchStatistics);
            });
        }
        transactionBatchRepository.save(txBatch);


        log.info("EXPENSIVE::Transaction batch status and statistics updated, batchId: {}", batchId);
    }

    @Transactional
    public void updateBatchesPerTransactions(Map<String, TxStatusUpdate> txStatusUpdates) {
        for (TxStatusUpdate txStatusUpdate : txStatusUpdates.values()) {
            String txId = txStatusUpdate.getTxId();
            Set<TransactionBatchAssocEntity> transactionBatchAssocsSet = transactionBatchAssocRepository.findAllByTxId(txId);

            if (transactionBatchAssocsSet.isEmpty()) {
                log.warn("Transaction batch assoc not found for id: {}", txId);
                continue;
            }

            Set<String> allBatchesIdsAssociatedWithThisTransaction = transactionBatchAssocsSet.stream()
                    .map(id -> id.getId().getTransactionBatchId())
                    .collect(Collectors.toSet());

            for (TransactionBatchEntity txBatch : transactionBatchRepository.findAllById(allBatchesIdsAssociatedWithThisTransaction)) {
                updateTransactionBatchStatusAndStats(txBatch.getId(), totalTxCount(txBatch, Optional.empty()).get(), Optional.empty());
            }
        }
    }

    @Transactional
    public List<TransactionBatchEntity> findAll() {
        return transactionBatchRepository.findAll();
    }

    private static Optional<Integer> totalTxCount(TransactionBatchEntity txBatch,
                                                  Optional<Integer> totalTransactionsCount) {
        return Optional.ofNullable(totalTransactionsCount
                .orElse(txBatch.getBatchStatistics().
                        flatMap(batchStatistics -> Optional.of(batchStatistics.getTotal()))
                        .orElse(null)));
    }

}
