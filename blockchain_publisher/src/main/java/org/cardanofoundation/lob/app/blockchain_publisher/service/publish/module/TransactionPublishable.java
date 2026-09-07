package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Limit;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Batch;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.TransactionEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch.DispatchingStrategy;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.CardanoPublishable;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.transaction.API1L1TransactionCreator;

/**
 * Publishable module for accounting transactions (API1): multiple transactions are batched into a single Cardano
 * transaction and dispatched under a pessimistic lock.
 */
@Service
@RequiredArgsConstructor
public class TransactionPublishable implements CardanoPublishable<TransactionEntity> {

    private final TransactionEntityRepositoryGateway repositoryGateway;
    private final API1L1TransactionCreator l1TransactionCreator;
    private final LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;
    private final DispatchingStrategy<TransactionEntity> dispatchingStrategy;

    @Override
    public String type() {
        return "transactions";
    }

    @Override
    public Set<String> claimReadyToDispatch(String organisationId, int batchSize) {
        return repositoryGateway.claimTransactionsReadyToBeDispatched(organisationId, batchSize, dispatchingStrategy);
    }

    @Override
    public Set<TransactionEntity> loadByIds(Set<String> ids) {
        return repositoryGateway.findAllByIdsPreservingOrder(ids);
    }

    @Override
    public Collection<Set<TransactionEntity>> groupForDispatch(Set<TransactionEntity> toDispatch) {
        // API1 batches all ready transactions into a single Cardano transaction; the creator decides how many
        // actually fit and returns the rest as "remaining".
        return List.of(toDispatch);
    }

    @Override
    public Either<ProblemDetail, Optional<L1Batch<TransactionEntity>>> buildL1Transaction(String organisationId, Set<TransactionEntity> unit) {
        return l1TransactionCreator.pullBlockchainTransaction(organisationId, unit);
    }

    @Override
    public Set<TransactionEntity> findNotFinalizedYet(String organisationId, Limit limit) {
        return repositoryGateway.findDispatchedTransactionsThatAreNotFinalizedYet(organisationId, limit);
    }

    @Override
    public void store(TransactionEntity entity) {
        repositoryGateway.storeTransaction(entity);
    }

    @Override
    public void storeAll(Set<TransactionEntity> entities) {
        repositoryGateway.storeTransactions(entities);
    }

    @Override
    public void unlock(Set<TransactionEntity> entities) {
        repositoryGateway.unlockTransactions(entities);
    }

    @Override
    public void notifyLedgerUpdate(String organisationId, Set<TransactionEntity> entities) {
        ledgerUpdatedEventPublisher.send(organisationId, LedgerUpdateType.TRANSACTION, entities);
    }

    @Override
    public DispatchingStrategy<TransactionEntity> dispatchingStrategy() {
        return dispatchingStrategy;
    }

}
