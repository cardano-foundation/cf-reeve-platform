package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus.notFinalisedButVisibleOnChain;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.Sets;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionItemEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch.DispatchingStrategy;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
@Getter
public class TransactionEntityRepositoryGateway {

    private final TransactionEntityRepository transactionEntityRepository;
    private final TransactionItemEntityRepository transactionItemEntityRepository;
    private final Clock clock;

    @Value("${lob.blockchain_publisher.dispatcher.lock_timeout:PT3H}") // Default grace period to 3 hours
    private Duration lockTimeoutDuration;

    @Value("${lob.blockchain-publisher.rollback.enabled:false}")
    private Optional<Boolean> rollbackEnabled;

    public Optional<TransactionEntity> findById(String txId) {
        return transactionEntityRepository.findById(txId);
    }

    public List<TransactionEntity> findAllById(Set<String> txIds) {
        return transactionEntityRepository.findAllById(txIds);
    }

    /**
     * Atomically claims transactions ready for dispatch: the locking read (FOR UPDATE SKIP LOCKED) and the
     * lockedAt write commit together, before this method returns (REQUIRES_NEW), so a concurrent dispatcher
     * instance can never claim the same rows. Rows read but not selected by the strategy are released at commit
     * without ever being marked. Returns the claimed ids in dispatch order.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Set<String> claimTransactionsReadyToBeDispatched(String organisationId,
                                                            int pullTransactionsBatchSize,
                                                            DispatchingStrategy<TransactionEntity> dispatchingStrategy) {
        Set<BlockchainPublishStatus> dispatchStatuses = BlockchainPublishStatus.toDispatchStatuses();

        Set<TransactionEntity> free = transactionEntityRepository.findFreeTransactionsByStatus(
                organisationId,
                dispatchStatuses,
                LocalDateTime.now(clock).minus(lockTimeoutDuration),
                Limit.of(pullTransactionsBatchSize));

        Set<TransactionEntity> toDispatch = dispatchingStrategy.apply(organisationId, free);

        LocalDateTime now = LocalDateTime.now(clock);
        toDispatch.forEach(tx -> tx.setLockedAt(now));

        return toDispatch.stream()
                .map(TransactionEntity::getId)
                .collect(toCollection(LinkedHashSet::new));
    }

    public Set<TransactionEntity> findAllByIdsPreservingOrder(Set<String> ids) {
        Map<String, TransactionEntity> byId = transactionEntityRepository.findAllById(ids).stream()
                .collect(toMap(TransactionEntity::getId, identity()));

        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .collect(toCollection(LinkedHashSet::new));
    }

    public Set<TransactionEntity> findDispatchedTransactionsThatAreNotFinalizedYet(String organisationId, Limit limit) {
        Set<BlockchainPublishStatus> notFinalisedButVisibleOnChain = notFinalisedButVisibleOnChain();

        return transactionEntityRepository.findDispatchedTransactionsThatAreNotFinalizedYet(organisationId, notFinalisedButVisibleOnChain, limit);
    }

    /**
     * Store only new transactions. We want our interface to be idempotent so if somebody sents the same transaction
     * we will ignore it.
     *
     * @param transactionEntities transactionEntities to be stored
     * @return stored transactions
     */
    @Transactional
    public Set<TransactionEntity> storeOnlyNew(Set<TransactionEntity> transactionEntities) {
        log.info("StoreOnlyNewTransactions..., storeOnlyNewTransactions:{}", transactionEntities.size());

        Set<String> txIds = transactionEntities.stream()
                .map(TransactionEntity::getId)
                .collect(toSet());

        Set<TransactionEntity> existingTransactions = new HashSet<>(transactionEntityRepository
                .findAllById(txIds));

        Sets.SetView<TransactionEntity> newTransactions = Sets.difference(transactionEntities, existingTransactions);

        Set<TransactionEntity> newTxs = Stream.concat(transactionEntityRepository.saveAll(newTransactions)
                        .stream(), existingTransactions.stream())
                .collect(toSet());

        for (TransactionEntity tx : newTxs) {
            transactionItemEntityRepository.saveAll(tx.getItems());
        }

        return newTxs;
    }

    @Transactional
    public Set<TransactionEntity> updateErrorRollbackTransactions(Set<TransactionEntity> transactionEntities) {
        Set<String> txIds = transactionEntities.stream()
                .map(TransactionEntity::getId)
                .collect(toSet());

        Set<TransactionEntity> existingTransactions = new HashSet<>(transactionEntityRepository
                .findByIdsAndPublishStatus(txIds, BlockchainPublishStatus.ERROR));

        log.info("updateErrorRollbackTransactions..., updateErrorRollbackTransactions:{}", transactionEntities.size());

        for (TransactionEntity existingEntity : existingTransactions) {
            // Find the corresponding incoming transaction
            TransactionEntity incomingTx = transactionEntities.stream()
                    .filter(tx -> tx.getId().equals(existingEntity.getId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Matching transaction not found"));

            // Update the existing entity with new values
            existingEntity.setInternalNumber(incomingTx.getInternalNumber());
            existingEntity.setL1SubmissionData(Optional.ofNullable(L1SubmissionData.builder()
                    .publishStatus(BlockchainPublishStatus.ROLLBACKED)
                    .build()));

            // Update or add items
            if (incomingTx.getItems() != null) {
                Set<String> incomingItemIds = incomingTx.getItems().stream()
                        .map(TransactionItemEntity::getId)
                        .collect(toSet());

                // Remove items that are not in the incoming set
                existingEntity.getItems().removeIf(item -> !incomingItemIds.contains(item.getId()));

                // Update existing items or add new ones
                for (var incomingItem : incomingTx.getItems()) {
                    TransactionItemEntity existingItem = existingEntity.getItems().stream()
                            .filter(item -> item.getId().equals(incomingItem.getId()))
                            .findFirst()
                            .orElse(null);

                    if (existingItem != null) {
                        // Update existing item fields
                        updateItemFields(existingItem, incomingItem);
                    } else {
                        // Create new item and add to collection
                        TransactionItemEntity newItem = new TransactionItemEntity();
                        newItem.setId(incomingItem.getId());
                        newItem.setTransaction(existingEntity);
                        updateItemFields(newItem, incomingItem);
                        existingEntity.getItems().add(newItem);
                    }
                }
            } else {
                existingEntity.getItems().clear();
            }
        }
        transactionEntityRepository.saveAll(existingTransactions);
        return existingTransactions;
    }

    private void updateItemFields(TransactionItemEntity target, TransactionItemEntity source) {
        target.setAmountFcy(source.getAmountFcy());
        target.setAmountLcy(source.getAmountLcy());
        target.setAccountEvent(source.getAccountEvent().orElse(null));
        target.setFxRate(source.getFxRate());
        target.setProject(source.getProject().orElse(null));
        target.setCostCenter(source.getCostCenter().orElse(null));
        target.setDocument(source.getDocument());
    }

    public void storeTransaction(TransactionEntity transactionEntity) {
        transactionEntityRepository.save(transactionEntity);
    }

    public void storeTransactions(Set<TransactionEntity> successfullyUpdatedTxEntities) {
        transactionEntityRepository.saveAll(successfullyUpdatedTxEntities);
    }

    public void unlockTransactions(Set<TransactionEntity> transactionsBatch) {
        transactionsBatch.forEach(transactionEntity ->
                transactionEntity.setLockedAt(null));
        transactionEntityRepository.saveAll(transactionsBatch);
    }

    @Transactional
    public void removePublishedTransactions(String id) {
        TransactionEntity transaction = transactionEntityRepository.findById(id).orElse(null);
        if (transaction == null) {
            return;
        }

        transaction.setL1SubmissionData(Optional.ofNullable(L1SubmissionData.builder()
                .publishStatus(BlockchainPublishStatus.ERROR)
                .build()));
        transactionEntityRepository.save(transaction);
        //transactionEntityRepository.deleteById(id);
    }
}
