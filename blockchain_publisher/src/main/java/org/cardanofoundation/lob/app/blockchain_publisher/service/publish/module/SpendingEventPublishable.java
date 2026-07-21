package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Limit;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Batch;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingEventEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.SpendingEventEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.CardanoPublishable;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.spendingevent.SpendingEventL1TransactionCreator;

/**
 * Publishable module for spending events (funding module). Batched with a pessimistic lock, mirroring the
 * transaction flow.
 *
 * <p>NOTE: the actual L1 transaction building is a placeholder until the spending-event metadata schema is
 * defined (see {@link SpendingEventL1TransactionCreator}). The framework wiring is complete, so once the real
 * serialiser lands no changes are needed here or in the generic engine.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpendingEventPublishable implements CardanoPublishable<SpendingEventEntity> {

    private final SpendingEventEntityRepositoryGateway repositoryGateway;
    private final SpendingEventL1TransactionCreator l1TransactionCreator;
    private final LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;

    @Override
    public String type() {
        return "spendingEvents";
    }

    @Override
    public Set<SpendingEventEntity> findReadyToDispatch(String organisationId, int batchSize) {
        return repositoryGateway.findEventsReadyToBeDispatched(organisationId, batchSize);
    }

    @Override
    public Collection<Set<SpendingEventEntity>> groupForDispatch(Set<SpendingEventEntity> toDispatch) {
        return List.of(toDispatch);
    }

    @Override
    public Either<ProblemDetail, Optional<L1Batch<SpendingEventEntity>>> buildL1Transaction(String organisationId, Set<SpendingEventEntity> unit) {
        return l1TransactionCreator.pullBlockchainTransaction(organisationId, unit);
    }

    @Override
    public Set<SpendingEventEntity> findNotFinalizedYet(String organisationId, Limit limit) {
        return repositoryGateway.findDispatchedEventsThatAreNotFinalizedYet(organisationId, limit);
    }

    @Override
    public void store(SpendingEventEntity entity) {
        repositoryGateway.store(entity);
    }

    @Override
    public void storeAll(Set<SpendingEventEntity> entities) {
        repositoryGateway.storeAll(entities);
    }

    @Override
    public boolean supportsLocking() {
        return true;
    }

    @Override
    public void lock(Set<SpendingEventEntity> entities) {
        repositoryGateway.lock(entities);
    }

    @Override
    public void unlock(Set<SpendingEventEntity> entities) {
        repositoryGateway.unlock(entities);
    }

    @Override
    public void notifyLedgerUpdate(String organisationId, Set<SpendingEventEntity> entities) {
        ledgerUpdatedEventPublisher.send(organisationId, LedgerUpdateType.SPENDING_EVENT, entities);
    }

}
