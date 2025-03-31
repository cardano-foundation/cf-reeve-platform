package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus.FINALIZED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationRejectionCode.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.javers.core.Javers;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.Reconcilation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationRejectionCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationViolation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionReconcilationRepository;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionReconcilationService {

    private final TransactionReconcilationRepository transactionReconcilationRepository;
    private final TransactionRepositoryGateway transactionRepositoryGateway;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final BlockchainReaderPublicApiIF blockchainReaderPublicApi;
    private final Javers javers;

    public Optional<ReconcilationEntity> findById(String reconcilationId) {
        return transactionReconcilationRepository.findById(reconcilationId);
    }

    @Transactional
    public void createReconcilation(String reconcilationId,
                                    String organisationId,
                                    LocalDate from,
                                    LocalDate to
    ) {
        log.info("Creating transaction reconcilation entity," +
                        " reconcilationId: {}," +
                        " from: {}," +
                        " to: {}",
                reconcilationId, from, to
        );
        Optional<ReconcilationEntity> entity = transactionReconcilationRepository.findById(reconcilationId);
        if(entity.isPresent()) {
            log.warn("Reconcilation already exists, reconcilationId: {}", reconcilationId);
            return;
        }
        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        reconcilationEntity.setId(reconcilationId);
        reconcilationEntity.setOrganisationId(organisationId);
        reconcilationEntity.setStatus(ReconcilationStatus.CREATED);
        reconcilationEntity.setFrom(Optional.of(from));
        reconcilationEntity.setTo(Optional.of(to));
        reconcilationEntity.setViolations(new LinkedHashSet<>());

        transactionReconcilationRepository.save(reconcilationEntity);

        log.info("Reconcilation created, reconcilationId: {}", reconcilationId);

        applicationEventPublisher.publishEvent(ReconcilationCreatedEvent.builder()
                .reconciliationId(reconcilationId)
                .metadata(EventMetadata.create(ReconcilationCreatedEvent.VERSION))
                .organisationId(organisationId)
                .from(from)
                .to(to)
                .build()
        );
    }

    @Transactional
    public void failReconcilation(String reconcilationId,
                                  String organisationId,
                                  Optional<LocalDate> from,
                                  Optional<LocalDate> to,
                                  FatalError error) {
        log.info("Failing transaction reconcilation entity," +
                        " reconcilationId: {}," +
                        " from: {}," +
                        " to: {}," +
                        " error: {}",
                reconcilationId, from, to, error
        );
        val reconcilationEntityM = transactionReconcilationRepository.findById(reconcilationId);

        var reconcilationEntity = new ReconcilationEntity();
        if (reconcilationEntityM.isPresent()) {
            reconcilationEntity = reconcilationEntityM.orElseThrow();
        } else {
            reconcilationEntity.setOrganisationId(organisationId);
            reconcilationEntity.setId(reconcilationId);
            reconcilationEntity.setFrom(from);
            reconcilationEntity.setTo(to);
        }

        reconcilationEntity.setStatus(ReconcilationStatus.FAILED);
        reconcilationEntity.setDetails(Optional.of(Details.builder()
                .code(error.getCode().name())
                .subCode(error.getSubCode())
                .bag(error.getBag())
                .build())
        );

        transactionReconcilationRepository.save(reconcilationEntity);

        log.info("Reconcilation failed, reconcilationId: {}", reconcilationId);
    }

    @Transactional
    public void reconcileChunk(String reconcilationId,
                               String organisationId,
                               LocalDate fromDate,
                               LocalDate toDate,
                               Set<TransactionEntity> detachedChunkTxs) {
        log.info("Reconciling transactions, reconcilationId: {}, organisation: {}, from: {}, to:{}, size: {}",
                reconcilationId, organisationId, fromDate, toDate, detachedChunkTxs.size()
        );

        // convert detachedChunkTxs to a map so we can easily loop through them
        val detachedChunkTxsMap = detachedChunkTxs.stream()
                .collect(Collectors.toMap(TransactionEntity::getId, tx -> tx));

        val reconcilationEntityM = transactionReconcilationRepository.findById(reconcilationId);
        if (reconcilationEntityM.isEmpty()) {
            log.error("Reconcilation entity not found, reconcilationId: {}", reconcilationId);

            failReconcilation(
                    reconcilationId,
                    organisationId,
                    Optional.of(fromDate),
                    Optional.of(toDate),
                    new FatalError(FatalError.Code.ADAPTER_ERROR, "RECONCILATION_NOT_FOUND", Map.of())
            );

            return;
        }

        val reconcilationEntity = reconcilationEntityM.get();
        reconcilationEntity.incrementMissingTxsCount(detachedChunkTxs.size());
        reconcilationEntity.setStatus(ReconcilationStatus.STARTED);

        val attachedTxEntities = transactionRepositoryGateway.findByAllId(
                detachedChunkTxs.stream().map(TransactionEntity::getId).collect(Collectors.toSet())
        );

        val attachedTxIds = attachedTxEntities.stream()
                .map(TransactionEntity::getId)
                .collect(Collectors.toSet());

        // Find transactions in detachedChunkTxs but not in attachedTxEntities
        val transactionsNotInAttached = detachedChunkTxs.stream()
                .filter(tx -> !attachedTxIds.contains(tx.getId()))
                .collect(Collectors.toSet());

        for (val tx : transactionsNotInAttached) {
            log.warn("Transaction not found in LOB DB yet, needs import, transactionId: {}", tx.getId());

            reconcilationEntity.addViolation(ReconcilationViolation.builder()
                    .transactionId(tx.getId())
                    .rejectionCode(ReconcilationRejectionCode.TX_NOT_IN_LOB)
                    .transactionInternalNumber(tx.getTransactionInternalNumber())
                    .transactionEntryDate(tx.getEntryDate())
                    .transactionType(tx.getTransactionType())
                    .amountLcySum(tx.getItems().stream()
                            .map(TransactionItemEntity::getAmountLcy)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                    )
                    .build());
        }

        val isOnChainE = blockchainReaderPublicApi.isOnChain(attachedTxEntities.stream()
                .map(TransactionEntity::getId)
                .collect(Collectors.toSet())
        );
        if (isOnChainE.isLeft()) {
            log.error("Error checking if transactions are on chain, reconcilationId: {}", reconcilationId);
            failReconcilation(
                    reconcilationId,
                    organisationId,
                    Optional.of(fromDate),
                    Optional.of(toDate),
                    new FatalError(FatalError.Code.ADAPTER_ERROR, "ERROR_CHECKING_ON_CHAIN", Map.of())
            );

            return;
        }
        val isOnChainMap = isOnChainE.get();

        for (val attachedTx : attachedTxEntities) {
            val detachedTx = detachedChunkTxsMap.get(attachedTx.getId()); // detachedTx can never be null since we using detatched tx ids as a way to find our attached txs

            val attachedTxHash = ERPSourceTransactionVersionCalculator.compute(attachedTx);
            val detachedTxHash = ERPSourceTransactionVersionCalculator.compute(detachedTx);
            log.info("Reconciling transaction, tx id:{}, txInternalNumber:{}, attachedTxHash:{}, detachedTxHash:{}",
                    attachedTx.getId(), attachedTx.getTransactionInternalNumber(), attachedTxHash, detachedTxHash);

            val sourceReconcilationStatus = attachedTxHash.equals(detachedTxHash)
                    ? ReconcilationCode.OK : ReconcilationCode.NOK;

            if (sourceReconcilationStatus == ReconcilationCode.NOK) {
                val sourceDiff = javers.compare(attachedTx, detachedTx);
                val changes = sourceDiff.getChanges();
                val jsonDiff = javers.getJsonConverter().toJson(changes);

                log.warn("Tx source version issue, tx id:{}, txInternalNumber:{}, diff:{}", detachedTx.getId(), detachedTx.getTransactionInternalNumber(), sourceDiff.prettyPrint());

                reconcilationEntity.addViolation(ReconcilationViolation.builder()
                        .transactionId(attachedTx.getId())
                        .rejectionCode(SOURCE_RECONCILATION_FAIL)
                        .sourceDiff(jsonDiff)
                        .transactionInternalNumber(attachedTx.getTransactionInternalNumber())
                        .transactionEntryDate(attachedTx.getEntryDate())
                        .transactionType(attachedTx.getTransactionType())
                        .amountLcySum(computeAmountLcySum(attachedTx)
                        )

                        .build());
            }

            val sinkReconcilationCode = getSinkReconcilationStatus(attachedTx, isOnChainMap);

            if (sinkReconcilationCode == ReconcilationCode.NOK) {
                reconcilationEntity.addViolation(ReconcilationViolation.builder()
                        .transactionId(attachedTx.getId())
                        .rejectionCode(SINK_RECONCILATION_FAIL)
                        .transactionInternalNumber(attachedTx.getTransactionInternalNumber())
                        .transactionEntryDate(attachedTx.getEntryDate())
                        .transactionType(attachedTx.getTransactionType())
                        .amountLcySum(computeAmountLcySum(attachedTx)
                        )
                        .build());
            }

            // we check only existence of LOB transaction on chain, we do not actually check the content and hashes, etc
            attachedTx.setReconcilation(Optional.of(Reconcilation.builder()
                    .source(sourceReconcilationStatus)
                    .sink(getSinkReconcilationStatus(attachedTx, isOnChainMap))
                    .build())
            );
            attachedTx.setLastReconcilation(Optional.of(reconcilationEntity));
        }

        // we can only store back the attached transactions, detatched transactions may not be in db
        // hibernate will store all reconcilation status updates
        transactionRepositoryGateway.storeAll(attachedTxEntities);

        log.info("Saving reconcilation entity, reconcilationId: {}", reconcilationEntity.getId());

        transactionReconcilationRepository.save(reconcilationEntity);

        log.info("Finished reconciling transactions.");
    }

    private static BigDecimal computeAmountLcySum(TransactionEntity attachedTx) {
        return attachedTx.getItems().stream()
                .map(TransactionItemEntity::getAmountLcy)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static ReconcilationCode getSinkReconcilationStatus(TransactionEntity attachedTx, Map<String, Boolean> isOnChainMap) {
        val isLOBTxOnChain = Optional.ofNullable(isOnChainMap.get(attachedTx.getId())).orElse(false);

        var sinkReconcilationStatus = ReconcilationCode.NOK;
        if (isLOBTxOnChain && attachedTx.getLedgerDispatchStatus() == FINALIZED) {
            sinkReconcilationStatus = ReconcilationCode.OK;
        }

        return sinkReconcilationStatus;
    }

    @Transactional
    public void wrapUpReconcilation(String reconcilationId,
                                    String organisationId,
                                    long total) {
        log.info("Wrapping up reconcilation, reconcilationId: {}", reconcilationId);

        val reconcilationEntityM = transactionReconcilationRepository.findById(reconcilationId);
        if (reconcilationEntityM.isEmpty()) {
            log.error("Reconcilation entity not found, reconcilationId: {}", reconcilationId);

            failReconcilation(
                    reconcilationId,
                    organisationId,
                    Optional.empty(),
                    Optional.empty(),
                    new FatalError(FatalError.Code.ADAPTER_ERROR, "RECONCILATION_NOT_FOUND", Map.of())
            );

            return;
        }
        val reconcilationEntity = reconcilationEntityM.get();
        if (total != reconcilationEntity.getProcessedTxCount()) {
            log.info("\n\nReconciliation not ready to proceed, reconcilationId: {}\n\n", reconcilationEntity.getId());
            return;
        }

        if (reconcilationEntity.getStatus() == ReconcilationStatus.COMPLETED) {
            log.warn("Reconcilation already completed, reconcilationId: {}", reconcilationEntity.getId());
            return;
        }

        log.info("Wrapping up reconcilation, reconcilationId: {}", reconcilationEntity.getId());

        val fromDate = reconcilationEntity.getFrom().orElseThrow();
        val toDate = reconcilationEntity.getTo().orElseThrow();

        val missingTxs = transactionRepositoryGateway.findAllByDateRangeAndNotReconciledYet(organisationId, fromDate, toDate);
        // we have some txs which are in LOB (db) but are missing in the ERP (likely some technical defect)

        log.info("Missing txs in ERP but found in LOB, size: {}", missingTxs.size());

        for (val missingTx : missingTxs) {
            log.error("Transaction missing in ERP but was found in the DB, transactionId: {}", missingTx.getId());

            missingTx.setReconcilation(Optional.of(Reconcilation.builder()
                    .source(ReconcilationCode.NOK)
                    .sink(ReconcilationCode.NOK)
                    .build())
            );

            reconcilationEntity.addViolation(ReconcilationViolation.builder()
                    .transactionId(missingTx.getId())
                    .rejectionCode(TX_NOT_IN_ERP)
                    .transactionInternalNumber(missingTx.getTransactionInternalNumber())
                    .transactionEntryDate(missingTx.getEntryDate())
                    .transactionType(missingTx.getTransactionType())
                    .amountLcySum(computeAmountLcySum(missingTx)
                    )
                    .build()
            );

            missingTx.setLastReconcilation(Optional.of(reconcilationEntity));
        }

        transactionRepositoryGateway.storeAll(missingTxs);

        reconcilationEntity.setStatus(ReconcilationStatus.COMPLETED);

        reconcilationEntity.incrementMissingTxsCount(missingTxs.size());
    }

}
