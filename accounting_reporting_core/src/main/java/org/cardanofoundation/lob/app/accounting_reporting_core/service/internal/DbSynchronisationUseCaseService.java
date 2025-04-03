package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static java.util.stream.Collectors.toMap;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.TX_VERSION_CONFLICT_TX_NOT_MODIFIABLE;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.WARN;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OrganisationTransactions;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchAssocEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchAssocRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.ProcessorFlags;

@Service
@Slf4j
@RequiredArgsConstructor
public class DbSynchronisationUseCaseService {

    private final AccountingCoreTransactionRepository accountingCoreTransactionRepository;
    private final TransactionConverter transactionConverter;
    private final TransactionItemRepository transactionItemRepository;
    private final TransactionBatchAssocRepository transactionBatchAssocRepository;
    private final TransactionBatchService transactionBatchService;

    @Transactional
    public void execute(String batchId,
                        OrganisationTransactions incomingTransactions,
                        int totalTransactionsCount,
                        ProcessorFlags flags) {
        ProcessorFlags.Trigger trigger = flags.getTrigger();
        Set<TransactionEntity> transactions = incomingTransactions.transactions();

        if (transactions.isEmpty()) {
            log.info("No transactions to process, batchId: {}", batchId);
            transactionBatchService.updateTransactionBatchStatusAndStats(batchId, Optional.of(totalTransactionsCount));

            return;
        }

        if (trigger == ProcessorFlags.Trigger.REPROCESSING) {
            // TODO should we check if we are NOT changing incomingTransactions which are already marked as dispatched?
            storeUpdatedTransactions(batchId, incomingTransactions.transactions(), flags);
            updateBatchAssoc(batchId, incomingTransactions.transactions(), new LinkedHashSet<>());
            return;
        }

        processTransactionsForTheFirstTime(batchId, transactions, Optional.of(totalTransactionsCount), flags);
    }

    @Transactional
    public void processTransactionsForTheFirstTime(String batchId,
                                                    Set<TransactionEntity> incomingDetachedTransactions,
                                                    Optional<Integer> totalTransactionsCount,
                                                    ProcessorFlags flags) {
        LinkedHashSet<TransactionEntity> txsAlreadyStored = new LinkedHashSet<>();

        Set<String> txIds = incomingDetachedTransactions.stream()
                .map(TransactionEntity::getId)
                .collect(Collectors.toSet());

        Map<String, TransactionEntity> databaseTransactionsMap = accountingCoreTransactionRepository.findAllById(txIds)
                .stream()
                .collect(toMap(TransactionEntity::getId, Function.identity()));

        LinkedHashSet<TransactionEntity> toProcessTransactions = new LinkedHashSet<>();

        for (TransactionEntity incomingTx : incomingDetachedTransactions) {
            Optional<TransactionEntity> txM = Optional.ofNullable(databaseTransactionsMap.get(incomingTx.getId()));

            boolean isDispatchMarked = txM.map(TransactionEntity::allApprovalsPassedForTransactionDispatch).orElse(false);
            boolean notStoredYet = txM.isEmpty();
            /** If is a new transaction || the new one is different from our Db copy || the transaction has an ERP source violation || transaction item has an ERP source rejection -> then should be processed*/
            boolean isChanged = notStoredYet || (txM.map(tx -> !isIncomingTransactionERPSame(tx, incomingTx)).orElse(false));

            if (isDispatchMarked && isChanged) {
                log.warn("Transaction cannot be altered, it is already marked as dispatched, transactionNumber: {}", incomingTx.getTransactionInternalNumber());
                processingDispatchedAndChangedTransaction(incomingTx, txM, txsAlreadyStored);
            }

            if (isDispatchMarked && !isChanged) {
                processingDispatchedAndNotChangedTransaction(incomingTx, txM, txsAlreadyStored);
            }

            if (isChanged && !isDispatchMarked) {
                processingNotDispatchedAndChangedTransaction(incomingTx, txM, toProcessTransactions);
            }
        }

        // updating BatchID of transactions that are already stored
        accountingCoreTransactionRepository.saveAll(txsAlreadyStored);

        storeUpdatedTransactions(batchId, toProcessTransactions, flags);

        updateBatchAssoc(batchId, toProcessTransactions, txsAlreadyStored);

        transactionBatchService.updateTransactionBatchStatusAndStats(batchId, totalTransactionsCount);
    }

    private void processingNotDispatchedAndChangedTransaction(TransactionEntity incomingTx, Optional<TransactionEntity> txM, LinkedHashSet<TransactionEntity> toProcessTransactions) {
        if (txM.isPresent()) {
            TransactionEntity attached = txM.orElseThrow();

            transactionConverter.copyFields(attached, incomingTx);
            attached.getAllItems().clear();
            attached.getAllItems().addAll(incomingTx.getAllItems());
            toProcessTransactions.add(attached);
        } else {
            toProcessTransactions.add(incomingTx);
        }
    }

    private static void processingDispatchedAndNotChangedTransaction(TransactionEntity incomingTx, Optional<TransactionEntity> txM, LinkedHashSet<TransactionEntity> txsAlreadyStored) {
        if (txM.isEmpty()) {
            return;
        }
        TransactionEntity transactionEntity = txM.get();
        transactionEntity.setBatchId(incomingTx.getBatchId());
        // Removing the TX Version Conflict since TX is the same as published
        Set<TransactionViolation> violations = transactionEntity.getViolations();
        boolean removedViolation = violations.removeIf(v -> v.getCode() == TX_VERSION_CONFLICT_TX_NOT_MODIFIABLE);
        if (removedViolation) {
            log.info("Removing TX Version Conflict violation for transactionNumber: {}", transactionEntity.getTransactionInternalNumber());
            transactionEntity.setViolations(violations);
            transactionEntity.recalcValidationStatus();
            txsAlreadyStored.add(transactionEntity);
        }
    }

    private void processingDispatchedAndChangedTransaction(TransactionEntity incomingTx, Optional<TransactionEntity> txM, LinkedHashSet<TransactionEntity> txsAlreadyStored) {
        if (txM.isEmpty()) {
            return;
        }
        TransactionEntity transactionEntity = txM.get();
        transactionEntity.setBatchId(incomingTx.getBatchId());
        // TODO we are breaking the rule here that violations are only raised in business rules code (e.g. business rules task items)
        TransactionViolation v = TransactionViolation.builder()
                .code(TX_VERSION_CONFLICT_TX_NOT_MODIFIABLE)
                .severity(WARN)
                .source(Source.ERP)
                .processorModule(this.getClass().getSimpleName())
                .bag(
                        Map.of(
                                "transactionNumber", transactionEntity.getTransactionInternalNumber()
                        )
                )
                .build();
        transactionEntity.addViolation(v);
        txsAlreadyStored.add(transactionEntity);
    }

    private void updateBatchAssoc(String batchId, Set<TransactionEntity> toProcessTransactions, Set<TransactionEntity> txsAlreadyStored) {
        Set<TransactionEntity> txs = new LinkedHashSet<>(toProcessTransactions);
        txs.addAll(txsAlreadyStored);

        Set<TransactionBatchAssocEntity> transactionBatchAssocEntities = txs
                .stream()
                .map(tx -> {
                    TransactionBatchAssocEntity.Id id = new TransactionBatchAssocEntity.Id(batchId, tx.getId());

                    return transactionBatchAssocRepository.findById(id).orElseGet(() -> new TransactionBatchAssocEntity(id));
                })
                .collect(Collectors.toSet());

        transactionBatchAssocRepository.saveAll(transactionBatchAssocEntities);
    }

    private void storeUpdatedTransactions(String batchId,
                                          Set<TransactionEntity> txs,
                                          ProcessorFlags flags) {
        log.info("Updating transaction batch, batchId: {}", batchId);
        ProcessorFlags.Trigger trigger = flags.getTrigger();

        for (TransactionEntity tx : txs) {
            TransactionEntity saved = accountingCoreTransactionRepository.save(tx);
            saved.getAllItems().forEach(i -> i.setTransaction(saved));

            /** Remove items rejection according to the processor selected */
            if (trigger == ProcessorFlags.Trigger.IMPORT) {
                tx.clearAllItemsRejectionsSource(Source.ERP);
            }
            if (trigger == ProcessorFlags.Trigger.REPROCESSING) {
                tx.clearAllItemsRejectionsSource(Source.LOB);
            }

            transactionItemRepository.saveAll(tx.getAllItems());
        }
    }

    private boolean isIncomingTransactionERPSame(TransactionEntity existingTx,
                                                 TransactionEntity incomingTx) {
        String existingTxVersion = ERPSourceTransactionVersionCalculator.compute(existingTx);
        String incomingTxVersion = ERPSourceTransactionVersionCalculator.compute(incomingTx);

        log.info("Existing transaction version:{}, incomingTx:{}", existingTxVersion, incomingTxVersion);

        return existingTxVersion.equals(incomingTxVersion);
    }

}
