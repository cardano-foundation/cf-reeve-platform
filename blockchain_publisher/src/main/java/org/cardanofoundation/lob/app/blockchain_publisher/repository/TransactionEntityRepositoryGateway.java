package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import static java.util.stream.Collectors.toSet;
import static org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus.notFinalisedButVisibleOnChain;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.Sets;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;

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

    public Optional<TransactionEntity> findById(String txId) {
        return transactionEntityRepository.findById(txId);
    }

    public Set<TransactionEntity> findAndLockTransactionsReadyToBeDispatched(String organisationId, int pullTransactionsBatchSize) {
        Set<BlockchainPublishStatus> dispatchStatuses = BlockchainPublishStatus.toDispatchStatuses();
        Limit limit = Limit.of(pullTransactionsBatchSize);

        Set<TransactionEntity> transactionsByStatus = transactionEntityRepository.findFreeTransactionsByStatus(
                organisationId,
                dispatchStatuses,
                LocalDateTime.now(clock),
                limit);
        if (transactionsByStatus.isEmpty()) {
            return transactionsByStatus;
        }
        // This logic could be moved to the repository, but for now it is easier to test it here
        Set<TransactionEntity> filteredTransactions = transactionsByStatus.stream().filter(
                transactionEntity -> (
                        transactionEntity.getLockedAt()
                                .map(lockedAt -> lockedAt.isBefore(LocalDateTime.now(clock).minus(lockTimeoutDuration)))
                                .orElse(true) // return true if lockedAt is not present
                        ))
                .collect(toSet());
        filteredTransactions.forEach(tx -> tx.setLockedAt(LocalDateTime.now(clock)));
        transactionEntityRepository.saveAll(filteredTransactions);
        return filteredTransactions;
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
}
