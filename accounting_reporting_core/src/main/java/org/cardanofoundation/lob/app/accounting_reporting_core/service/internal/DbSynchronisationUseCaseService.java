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
            storeTransactions(batchId, incomingTransactions, flags);
            return;
        }

        String organisationId = incomingTransactions.organisationId();

        processTransactionsForTheFirstTime(batchId, organisationId, transactions, Optional.of(totalTransactionsCount), flags);
    }

    @Transactional
    public void processTransactionsForTheFirstTime(String batchId,
                                                    String organisationId,
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
            boolean isChanged = notStoredYet || (txM.map(tx -> !isIncomingTransactionERPSame(tx, incomingTx) || tx.hasAnyRejection(Source.ERP) || tx.hasAnyViolation(Source.ERP)).orElse(false));

            if (isDispatchMarked && isChanged) {
                log.warn("Transaction cannot be altered, it is already marked as dispatched, transactionNumber: {}", incomingTx.getTransactionInternalNumber());
                txsAlreadyStored.add(incomingTx);
            }

            if (isChanged && !isDispatchMarked) {
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
        }

        raiseViolationForAlreadyProcessedTransactions(txsAlreadyStored);

        storeTransactions(batchId, new OrganisationTransactions(organisationId, toProcessTransactions), flags);

        transactionBatchService.updateTransactionBatchStatusAndStats(batchId, totalTransactionsCount);
    }

    private void storeTransactions(String batchId,
                                   OrganisationTransactions transactions,
                                   ProcessorFlags flags) {
        log.info("Updating transaction batch, batchId: {}", batchId);
        ProcessorFlags.Trigger trigger = flags.getTrigger();
        Set<TransactionEntity> txs = transactions.transactions();

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

        Set<TransactionBatchAssocEntity> transactionBatchAssocEntities = txs
                .stream()
                .map(tx -> {
                    TransactionBatchAssocEntity.Id id = new TransactionBatchAssocEntity.Id(batchId, tx.getId());

                    return transactionBatchAssocRepository.findById(id).orElseGet(() -> new TransactionBatchAssocEntity(id));
                })
                .collect(Collectors.toSet());

        transactionBatchAssocRepository.saveAll(transactionBatchAssocEntities);
    }

    private boolean isIncomingTransactionERPSame(TransactionEntity existingTx,
                                                 TransactionEntity incomingTx) {
        String existingTxVersion = ERPSourceTransactionVersionCalculator.compute(existingTx);
        String incomingTxVersion = ERPSourceTransactionVersionCalculator.compute(incomingTx);

        log.info("Existing transaction version:{}, incomingTx:{}", existingTxVersion, incomingTxVersion);

        return existingTxVersion.equals(incomingTxVersion);
    }

    // TODO we are breaking the rule here that violations are only raised in business rules code (e.g. business rules task items)
    private void raiseViolationForAlreadyProcessedTransactions(Set<TransactionEntity> txsAlreadyDispatched) {
        if (txsAlreadyDispatched.isEmpty()) {
            return;
        }

        log.info("txs causing conflict count:{}", txsAlreadyDispatched.size());

        for (TransactionEntity tx : txsAlreadyDispatched) {
            log.info("tx causing conflict: {}", tx);

            TransactionViolation v = TransactionViolation.builder()
                    .code(TX_VERSION_CONFLICT_TX_NOT_MODIFIABLE)
                    .severity(WARN)
                    .source(Source.ERP)
                    .processorModule(this.getClass().getSimpleName())
                    .bag(
                            Map.of(
                                    "transactionNumber", tx.getTransactionInternalNumber()
                            )
                    )
                    .build();

            tx.addViolation(v);
        }
    }

}
