package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationRejectionCode.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
import org.javers.core.Changes;
import org.javers.core.Javers;
import org.javers.core.diff.Diff;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.Reconcilation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Details;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
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
    private final Optional<IndexerReconcilationServiceIF> indexerReconcilationService;
    private final TransactionBatchService transactionBatchService;

    @Value("${lob.indexer.enabled:false}")
    private boolean indexerEnabled;

    public Optional<ReconcilationEntity> findById(String reconcilationId) {
        return transactionReconcilationRepository.findById(reconcilationId);
    }

    @Transactional
    public void createReconcilation(String reconcilationId,
                                    String organisationId,
                                    LocalDate from,
                                    LocalDate to,
                                    ExtractorType extractorType) {
        log.info("Creating transaction reconcilation entity," +
                        " reconcilationId: {}," +
                        " from: {}," +
                        " to: {}",
                reconcilationId, from, to
        );
        Optional<ReconcilationEntity> entity = transactionReconcilationRepository.findById(reconcilationId);
        if (entity.isPresent()) {
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

        transactionReconcilationRepository.saveAndFlush(reconcilationEntity);

        log.info("Reconcilation created, reconcilationId: {}", reconcilationId);

        applicationEventPublisher.publishEvent(ReconcilationCreatedEvent.builder()
                .reconciliationId(reconcilationId)
                .metadata(EventMetadata.create(ReconcilationCreatedEvent.VERSION))
                .organisationId(organisationId)
                .from(from)
                .to(to)
                .extractorType(extractorType)
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
        Optional<ReconcilationEntity> reconcilationEntityM = transactionReconcilationRepository.findById(reconcilationId);

        ReconcilationEntity reconcilationEntity;
        if (reconcilationEntityM.isPresent()) {
            reconcilationEntity = reconcilationEntityM.orElseThrow();
        } else {
            reconcilationEntity = new ReconcilationEntity();
            reconcilationEntity.setOrganisationId(organisationId);
            reconcilationEntity.setId(reconcilationId);
            reconcilationEntity.setFrom(from);
            reconcilationEntity.setTo(to);
            reconcilationEntity.setViolations(new LinkedHashSet<>());
        }

        reconcilationEntity.setStatus(ReconcilationStatus.FAILED);
        reconcilationEntity.setDetails(Optional.of(Details.builder()
                .code(error.getCode().name())
                .subCode(error.getSubCode())
                .bag(error.getBag())
                .build())
        );

        transactionReconcilationRepository.saveAndFlush(reconcilationEntity);

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
        Map<String, TransactionEntity> detachedChunkTxsMap = detachedChunkTxs.stream()
                .collect(Collectors.toMap(TransactionEntity::getId, tx -> tx));

        // Use pessimistic locking to prevent lost updates when multiple chunks run in parallel
        Optional<ReconcilationEntity> reconcilationEntityM = transactionReconcilationRepository.findReconcilationEntityById(reconcilationId);
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

        ReconcilationEntity reconcilationEntity = reconcilationEntityM.get();
        reconcilationEntity.incrementMissingTxsCount(detachedChunkTxs.size());
        reconcilationEntity.setStatus(ReconcilationStatus.STARTED);

        List<TransactionEntity> attachedTxEntities = transactionRepositoryGateway.findByAllId(
                detachedChunkTxs.stream().map(TransactionEntity::getId).collect(Collectors.toSet())
        );

        Set<String> attachedTxIds = attachedTxEntities.stream()
                .map(TransactionEntity::getId)
                .collect(Collectors.toSet());

        // Find transactions in detachedChunkTxs but not in attachedTxEntities
        Set<TransactionEntity> transactionsNotInAttached = detachedChunkTxs.stream()
                .filter(tx -> !attachedTxIds.contains(tx.getId()))
                .collect(Collectors.toSet());

        for (TransactionEntity tx : transactionsNotInAttached) {
            log.warn("Transaction not found in LOB DB yet, needs import, transactionId: {} ({})", tx.getInternalTransactionNumber(), tx.getId());

            reconcilationEntity.addViolation(ReconcilationViolation.builder()
                    .transactionId(tx.getId())
                    .rejectionCode(ReconcilationRejectionCode.TX_NOT_IN_LOB)
                    .transactionInternalNumber(tx.getInternalTransactionNumber())
                    .transactionEntryDate(tx.getEntryDate())
                    .transactionType(tx.getTransactionType())
                    .amountLcySum(tx.getItems().stream()
                            .map(TransactionItemEntity::getAmountLcy)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                    )
                    .build());
        }

        Either<ProblemDetail, Map<String, Boolean>> isOnChainE = blockchainReaderPublicApi.isOnChain(attachedTxEntities.stream()
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
        Map<String, Boolean> isOnChainMap = isOnChainE.get();

        for (TransactionEntity attachedTx : attachedTxEntities) {
            attachedTx.setLastReconcilation(Optional.empty()); // To avoid cyclical references when a new version exist in the ERP
            TransactionEntity detachedTx = detachedChunkTxsMap.get(attachedTx.getId()); // detachedTx can never be null since we are using detached tx ids as a way to find our attached txs
            detachedTx.setLastReconcilation(Optional.empty()); // Also clear on detached to prevent Javers null ID issues with Hibernate proxies

            String attachedTxHash = ERPSourceTransactionVersionCalculator.compute(attachedTx);
            String detachedTxHash = ERPSourceTransactionVersionCalculator.compute(detachedTx);
            log.info("Reconciling transaction, tx id:{}, txInternalNumber:{}, attachedTxHash:{}, detachedTxHash:{}",
                    attachedTx.getId(), attachedTx.getInternalTransactionNumber(), attachedTxHash, detachedTxHash);

            ReconcilationCode sourceReconcilationStatus = attachedTxHash.equals(detachedTxHash) || attachedTx.getExtractorType().equals(ExtractorType.CSV.name())
                    ? ReconcilationCode.OK : ReconcilationCode.NOK;

            if (sourceReconcilationStatus == ReconcilationCode.NOK) {
                Diff sourceDiff = javers.compare(attachedTx, detachedTx);
                Changes changes = sourceDiff.getChanges();
                String jsonDiff = javers.getJsonConverter().toJson(changes);

                log.warn("Tx source version issue, tx id:{}, txInternalNumber:{}, diff:{}", detachedTx.getId(), detachedTx.getInternalTransactionNumber(), sourceDiff.prettyPrint());

                reconcilationEntity.addViolation(ReconcilationViolation.builder()
                        .transactionId(attachedTx.getId())
                        .rejectionCode(SOURCE_RECONCILATION_FAIL)
                        .sourceDiff(jsonDiff)
                        .transactionInternalNumber(attachedTx.getInternalTransactionNumber())
                        .transactionEntryDate(attachedTx.getEntryDate())
                        .transactionType(attachedTx.getTransactionType())
                        .amountLcySum(computeAmountLcySum(attachedTx)
                        )

                        .build());
            }

            ReconcilationCode isSync = getSinkReconcilationStatus(attachedTx, isOnChainMap);
            // we check only existence of LOB transaction on chain, we do not actually check the content and hashes, etc
            attachedTx.setReconcilation(Optional.of(Reconcilation.builder()
                    .source(sourceReconcilationStatus)
                    .sink(isSync)
                    .build())
            );

            attachedTx.setLastReconcilation(Optional.of(reconcilationEntity));
        }

        // we can only store back the attached transactions, detatched transactions may not be in db
        // hibernate will store all reconcilation status updates
        transactionRepositoryGateway.storeAll(attachedTxEntities);

        log.info("Saving reconcilation entity, reconcilationId: {}", reconcilationEntity.getId());

        transactionReconcilationRepository.saveAndFlush(reconcilationEntity);

        processIndexerReconciliation(organisationId, fromDate, toDate, new HashSet<>(attachedTxEntities), reconcilationEntity);

        log.info("Finished reconciling transactions chunk.");
    }

    private static BigDecimal computeAmountLcySum(TransactionEntity attachedTx) {
        return attachedTx.getItems().stream()
                .map(TransactionItemEntity::getAmountLcy)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static ReconcilationCode getSinkReconcilationStatus(TransactionEntity attachedTx, Map<String, Boolean> isOnChainMap) {
        /*
        Old validation
                boolean isLOBTxOnChain = Optional.ofNullable(isOnChainMap.get(attachedTx.getId())).orElse(false);

         */
        // Check if there's an existing sink value
        if (attachedTx.getReconcilation().isPresent() &&
                attachedTx.getReconcilation().get().getSink().isPresent()) {
            return attachedTx.getReconcilation().get().getSink().get();
        }

        // If no existing sink value, return NOK
        return ReconcilationCode.NOK;
    }

    @Transactional
    public void wrapUpReconcilation(String reconcilationId,
                                    String organisationId,
                                    long total) {
        log.info("Wrapping up reconcilation, reconcilationId: {}", reconcilationId);

        Optional<ReconcilationEntity> reconcilationEntityM = transactionReconcilationRepository.findById(reconcilationId);
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
        ReconcilationEntity reconcilationEntity = reconcilationEntityM.get();
        if (total != reconcilationEntity.getProcessedTxCount()) {
            log.info("Reconciliation not ready to proceed, reconcilationId: {}", reconcilationEntity.getId(), total, reconcilationEntity.getProcessedTxCount());
            return;
        }

        if (reconcilationEntity.getStatus() == ReconcilationStatus.COMPLETED) {
            log.warn("Reconcilation already completed, reconcilationId: {}", reconcilationEntity.getId());
            if (indexerEnabled && indexerReconcilationService.isPresent()) {
                LocalDate fromDate = reconcilationEntity.getFrom().orElseThrow();
                LocalDate toDate = reconcilationEntity.getTo().orElseThrow();
                log.info("Starting indexer reconciliation after main reconciliation completed, reconcilationId: {}", reconcilationId);
                reconcileWithIndexer(reconcilationId, organisationId, fromDate, toDate);
            }
            return;
        }

        log.info("Wrapping up reconcilation for real, reconcilationId: {}", reconcilationEntity.getId());

        LocalDate fromDate = reconcilationEntity.getFrom().orElseThrow();
        LocalDate toDate = reconcilationEntity.getTo().orElseThrow();

        Set<TransactionEntity> missingTxs = transactionRepositoryGateway.findAllByDateRangeAndNotReconciledYet(organisationId, fromDate, toDate);
        // we have some txs which are in LOB (db) but are missing in the ERP (likely some technical defect)

        log.info("Missing txs in ERP but found in LOB, size: {}", missingTxs.size());

        for (TransactionEntity missingTx : missingTxs) {

            if (ExtractorType.CSV.name().equals(missingTx.getExtractorType()) || (missingTx.getReconcilation().isPresent() && missingTx.getReconcilation().get().getSource().filter(code -> code == ReconcilationCode.OK).isPresent())) {
                missingTx.setLastReconcilation(Optional.of(reconcilationEntity));
                missingTx.setReconcilation(Optional.of(Reconcilation.builder()
                        .source(ReconcilationCode.OK)
                        .build())
                );
                continue;
            }

            log.error("Transaction missing in ERP but was found in the DB, transactionId: {} ({})", missingTx.getInternalTransactionNumber(), missingTx.getId());

            missingTx.setReconcilation(Optional.of(Reconcilation.builder()
                    .source(ReconcilationCode.NOK)
                    .sink(ReconcilationCode.NOK)
                    .build())
            );

            if (!missingTx.getLedgerDispatchApproved()) {
                missingTx.setInternalTransactionNumber(missingTx.getInternalTransactionNumber());
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation violation =
                        org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation.builder()
                                .code(TransactionViolationCode.TX_NOT_IN_ERP)
                                .severity(Violation.Severity.ERROR)
                                .source(Source.ERP)
                                .processorModule("reconciliation")
                                .txItemId(missingTx.getId())
                                .build();

                missingTx.addViolation(violation);
                log.info("Created TransactionViolation for TX_NOT_IN_ERP, transactionId: {} ({})", missingTx.getInternalTransactionNumber(), missingTx.getId());
            } else {
                reconcilationEntity.addViolation(ReconcilationViolation.builder()
                        .transactionId(missingTx.getId())
                        .rejectionCode(TX_NOT_IN_ERP)
                        .transactionInternalNumber(missingTx.getInternalTransactionNumber())
                        .transactionEntryDate(missingTx.getEntryDate())
                        .transactionType(missingTx.getTransactionType())
                        .amountLcySum(computeAmountLcySum(missingTx)
                        )
                        .build()
                );
            }

            missingTx.setLastReconcilation(Optional.of(reconcilationEntity));
        }

        transactionRepositoryGateway.storeAll(missingTxs);

        reconcilationEntity.setStatus(ReconcilationStatus.COMPLETED);

        reconcilationEntity.incrementMissingTxsCount(missingTxs.size());
        transactionReconcilationRepository.saveAndFlush(reconcilationEntity);
        if (indexerEnabled && indexerReconcilationService.isPresent()) {
            log.info("Starting indexer reconciliation after main reconciliation completed, reconcilationId: {}", reconcilationId);
            reconcileWithIndexer(reconcilationId, organisationId, fromDate, toDate);
        }
        // updating all batches
        missingTxs.stream()
                .flatMap(txEntity -> txEntity.getBatches().stream().map(TransactionBatchEntity::getId)).collect(Collectors.toSet())
                .forEach(batchId -> transactionBatchService.invokeUpdateTransactionBatchStatusAndStats(batchId, Optional.empty(), Optional.empty()));

    }

    /**
     * Reconciles transactions from the database with the On-Chain Indexer.
     * This method follows the same pattern as reconcileChunk:
     * - Get chunk data to validate
     * - Get data from indexer
     * - Compare data and validate
     *
     * @param reconcilationId The reconciliation ID
     * @param organisationId  The organisation ID to reconcile
     * @param fromDate        Start date for reconciliation
     * @param toDate          End date for reconciliation
     */
    @Transactional
    public void reconcileWithIndexer(
            String reconcilationId,
            String organisationId,
            LocalDate fromDate,
            LocalDate toDate) {
        log.info("Reconciling transactions with indexer, reconcilationId: {}, organisation: {}, from: {}, to: {}",
                reconcilationId, organisationId, fromDate, toDate);


        Optional<ReconcilationEntity> reconcilationEntityM = transactionReconcilationRepository.findById(reconcilationId);
        if (reconcilationEntityM.isEmpty()) {
            log.error("Reconcilation entity not found, reconcilationId: {}", reconcilationId);
            return;
        }

        ReconcilationEntity reconcilationEntity = reconcilationEntityM.get();

        Set<TransactionEntity> attachedTxEntities = transactionRepositoryGateway.findAllByDateRange(organisationId, fromDate, toDate);

        processIndexerReconciliation(organisationId, fromDate, toDate, attachedTxEntities, reconcilationEntity);
    }

    private void processIndexerReconciliation(String organisationId, LocalDate fromDate, LocalDate toDate, Set<TransactionEntity> attachedTxEntities, ReconcilationEntity reconcilationEntity) {
        if (attachedTxEntities.isEmpty()) {
            log.warn("No attached transactions found for indexer reconciliation");
            return;
        }

        if (!indexerEnabled || indexerReconcilationService.isEmpty()) {
            return;
        }

        Set<TransactionEntity> attachedTxEntitiesSet = Set.copyOf(attachedTxEntities);

        IndexerReconcilationServiceIF indexerService = indexerReconcilationService
                .orElseThrow(() -> new IllegalStateException("Indexer reconciliation service is not available"));
        Either<ProblemDetail, Map<String, IndexerReconcilationServiceIF.IndexerReconcilationResult>> resultE =
                indexerService.reconcileWithIndexer(
                        organisationId,
                        fromDate,
                        toDate,
                        attachedTxEntitiesSet
                );

        if (resultE.isLeft()) {
            log.error("Indexer reconciliation failed: {}", resultE.getLeft().getDetail());

            for (TransactionEntity attachedTx : attachedTxEntities) {

                if (attachedTx.getReconcilation()
                        .flatMap(Reconcilation::getSink)
                        .filter(status -> status == ReconcilationCode.OK)
                        .isEmpty()) {
                    reconcilationEntity.addViolation(ReconcilationViolation.builder()
                            .transactionId(attachedTx.getId())
                            .rejectionCode(SINK_RECONCILATION_FAIL)
                            .transactionInternalNumber(attachedTx.getInternalTransactionNumber())
                            .transactionEntryDate(attachedTx.getEntryDate())
                            .transactionType(attachedTx.getTransactionType())
                            .amountLcySum(computeAmountLcySum(attachedTx)
                            )
                            .build());

                }
            }
            transactionReconcilationRepository.saveAndFlush(reconcilationEntity);
            return;
        }

        Map<String, IndexerReconcilationServiceIF.IndexerReconcilationResult> results = resultE.get();

        for (TransactionEntity tx : attachedTxEntities) {
            String txId = tx.getId();
            IndexerReconcilationServiceIF.IndexerReconcilationResult indexerResult = results.get(txId);

            ReconcilationCode sinkReconcilationStatus;

            if (indexerResult == null) {
                sinkReconcilationStatus = ReconcilationCode.NOK;
                log.warn("Transaction {} ({}) not found in indexer results", tx.getInternalTransactionNumber(), txId);
                reconcilationEntity.addViolation(ReconcilationViolation.builder()
                        .transactionId(txId)
                        .rejectionCode(SINK_RECONCILATION_FAIL)
                        .transactionInternalNumber(tx.getInternalTransactionNumber())
                        .transactionEntryDate(tx.getEntryDate())
                        .transactionType(tx.getTransactionType())
                        .amountLcySum(computeAmountLcySum(tx))
                        .build());
            } else if (indexerResult.status() == ReconcilationCode.NOK) {
                sinkReconcilationStatus = ReconcilationCode.NOK;
                log.warn("Transaction {} ({}) failed indexer reconciliation: {} ", tx.getInternalTransactionNumber(), txId, indexerResult.mismatchReason());
                reconcilationEntity.addViolation(ReconcilationViolation.builder()
                        .transactionId(txId)
                        .rejectionCode(SINK_RECONCILATION_FAIL)
                        .transactionInternalNumber(tx.getInternalTransactionNumber())
                        .transactionEntryDate(tx.getEntryDate())
                        .transactionType(tx.getTransactionType())
                        .amountLcySum(computeAmountLcySum(tx))
                        .build());
            } else {
                log.info("Transaction {} ({}) is OK", tx.getInternalTransactionNumber(), txId);
                sinkReconcilationStatus = ReconcilationCode.OK;
            }

            Reconcilation currentReconcilation = tx.getReconcilation().orElse(
                    Reconcilation.builder().build()
            );
            tx.setReconcilation(Optional.of(currentReconcilation.toBuilder()
                    .sink(sinkReconcilationStatus)
                    .build()));
            tx.setLastReconcilation(Optional.of(reconcilationEntity));
            //transactionRepositoryGateway.store(tx);
        }

        transactionRepositoryGateway.storeAll(attachedTxEntities);

        transactionReconcilationRepository.saveAndFlush(reconcilationEntity);

        long okCount = results.values().stream()
                .filter(r -> r.status() == ReconcilationCode.OK)
                .count();
        long nokCount = results.values().stream()
                .filter(r -> r.status() == ReconcilationCode.NOK)
                .count();

        log.info("Indexer reconciliation completed. Total: {}, OK: {}, NOK: {}",
                results.size(), okCount, nokCount);
    }

}
