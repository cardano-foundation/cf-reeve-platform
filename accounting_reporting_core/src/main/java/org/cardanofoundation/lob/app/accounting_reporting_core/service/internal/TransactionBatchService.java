package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionBatchStatus.*;
import static org.springframework.transaction.annotation.Propagation.SUPPORTS;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.SystemExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Details;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.FilteringParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchAssocEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchAssocRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepositoryGateway;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchStatisticsView;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;
import org.cardanofoundation.lob.app.support.reactive.Debouncer;
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
    private final TxBatchStatsCalculator txBatchStatsCalculator;
    private final DebouncerManager debouncerManager;

    @Value("${batch.stats.debounce.duration:PT3S}")
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
                                                     Integer totalTransactionsCount) {
        try {
            Debouncer debouncer = debouncerManager.getDebouncer(batchId, () -> invokeUpdateTransactionBatchStatusAndStats(batchId, totalTransactionsCount), batchStatsDebounceDuration);

            debouncer.call();
        } catch (ExecutionException e) {
            log.warn("Error while getting debouncer for batchId: {}", batchId, e);

            invokeUpdateTransactionBatchStatusAndStats(batchId, totalTransactionsCount);
        }
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

    private void invokeUpdateTransactionBatchStatusAndStats(String batchId,
                                                            int totalTransactionsCount) {
        log.info("EXPENSIVE::Updating transaction batch status and statistics, batchId: {}", batchId);

        Optional<TransactionBatchEntity> txBatchM = transactionBatchRepositoryGateway.findById(batchId);

        if (txBatchM.isEmpty()) {
            log.warn("Transaction batch not found for id: {}", batchId);
            return;
        }

        TransactionBatchEntity txBatch = txBatchM.orElseThrow();
        Optional<BatchStatisticsView> batchStatisticViewForBatchId = transactionBatchRepository.getBatchStatisticViewForBatchId(batchId);
        if(batchStatisticViewForBatchId.isEmpty()) {
            log.error("Transaction batch statistics not found for id: {}", batchId);

        } else {


            if (txBatch.getStatus() == FINALIZED) {
                log.warn("Transaction batch already finalized or failed, batchId: {}", batchId);
                return;
            }
            BatchStatisticsView batchStatisticsView = batchStatisticViewForBatchId.orElseThrow();

            txBatch.setBatchStatistics(batchStatisticsView.toBatchStatistics(totalTransactionsCount));
            txBatch.setStatus(txBatchStatusCalculator.reCalcStatus(batchStatisticsView, totalTransactionsCount));
            transactionBatchRepository.save(txBatch);
        }


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
                updateTransactionBatchStatusAndStats(txBatch.getId(), totalTxCount(txBatch, Optional.empty()).get());
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
