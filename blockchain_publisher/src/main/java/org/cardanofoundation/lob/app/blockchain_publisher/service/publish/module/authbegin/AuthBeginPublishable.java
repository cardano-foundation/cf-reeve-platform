package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.authbegin;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Limit;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Batch;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.authbegin.AuthBeginEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.AuthBeginEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.CardanoPublishable;

/**
 * Publishable module for CIP-170 AUTH_BEGIN: one ceremony per Cardano transaction, dispatched under a
 * lock so a slow submission cannot be picked up twice by overlapping dispatcher ticks.
 *
 * <p>The ledger update it emits is what completes the ceremony's AUTH_BEGIN step back in
 * {@code keri_attestation} — the entity id is the ceremony id, which is how the two correlate across
 * the process boundary.
 */
@Service
@RequiredArgsConstructor
public class AuthBeginPublishable implements CardanoPublishable<AuthBeginEntity> {

    private final AuthBeginEntityRepositoryGateway repositoryGateway;
    private final AuthBeginL1TransactionCreator l1TransactionCreator;
    private final LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;

    @Override
    public String type() {
        return "auth_begins";
    }

    @Override
    public Set<AuthBeginEntity> findReadyToDispatch(String organisationId, int batchSize) {
        return repositoryGateway.findAuthBeginsReadyToBeDispatched(organisationId, batchSize);
    }

    @Override
    public Collection<Set<AuthBeginEntity>> groupForDispatch(Set<AuthBeginEntity> toDispatch) {
        // one Cardano transaction per ceremony
        return toDispatch.stream()
                .<Set<AuthBeginEntity>>map(Set::of)
                .toList();
    }

    @Override
    public Either<ProblemDetail, Optional<L1Batch<AuthBeginEntity>>> buildL1Transaction(String organisationId, Set<AuthBeginEntity> unit) {
        AuthBeginEntity authBegin = unit.iterator().next();

        return l1TransactionCreator.pullBlockchainTransaction(authBegin)
                .map(tx -> Optional.of(new L1Batch<>(
                        organisationId,
                        Set.of(authBegin),
                        Set.of(),
                        tx.creationSlot(),
                        tx.serialisedTxData(),
                        tx.receiverAddress())));
    }

    @Override
    public Set<AuthBeginEntity> findNotFinalizedYet(String organisationId, Limit limit) {
        return repositoryGateway.findDispatchedAuthBeginsThatAreNotFinalizedYet(organisationId, limit);
    }

    @Override
    public void store(AuthBeginEntity entity) {
        repositoryGateway.store(entity);
    }

    @Override
    public void storeAll(Set<AuthBeginEntity> entities) {
        repositoryGateway.storeAll(entities);
    }

    @Override
    public boolean supportsLocking() {
        return true;
    }

    @Override
    public void lock(Set<AuthBeginEntity> entities) {
        repositoryGateway.lock(entities);
    }

    @Override
    public void unlock(Set<AuthBeginEntity> entities) {
        repositoryGateway.unlock(entities);
    }

    @Override
    public void notifyLedgerUpdate(String organisationId, Set<AuthBeginEntity> entities) {
        ledgerUpdatedEventPublisher.send(organisationId, LedgerUpdateType.AUTH_BEGIN, entities);
    }

}
