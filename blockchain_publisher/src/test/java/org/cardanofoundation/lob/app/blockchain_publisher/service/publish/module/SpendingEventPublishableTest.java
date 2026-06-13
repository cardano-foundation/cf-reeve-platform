package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Limit;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Batch;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingEventEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.SpendingEventEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.spendingevent.SpendingEventL1TransactionCreator;

@ExtendWith(MockitoExtension.class)
class SpendingEventPublishableTest {

    @Mock
    private SpendingEventEntityRepositoryGateway repositoryGateway;
    @Mock
    private SpendingEventL1TransactionCreator l1TransactionCreator;
    @Mock
    private LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;

    @InjectMocks
    private SpendingEventPublishable publishable;

    @Test
    void type_returnsSpendingEvents() {
        assertThat(publishable.type()).isEqualTo("spendingEvents");
    }

    @Test
    void findReadyToDispatch_delegatesToGateway() {
        SpendingEventEntity entity = new SpendingEventEntity();
        when(repositoryGateway.findEventsReadyToBeDispatched("org1", 10)).thenReturn(Set.of(entity));

        assertThat(publishable.findReadyToDispatch("org1", 10)).containsExactly(entity);
    }

    @Test
    void groupForDispatch_allEntitiesReturnedInOneBatch() {
        SpendingEventEntity e1 = new SpendingEventEntity();
        Set<SpendingEventEntity> input = Set.of(e1);

        Collection<Set<SpendingEventEntity>> groups = publishable.groupForDispatch(input);

        assertThat(groups).hasSize(1);
        assertThat(groups.iterator().next()).isSameAs(input);
    }

    @Test
    void buildL1Transaction_delegatesToCreator() {
        Set<SpendingEventEntity> unit = Set.of(new SpendingEventEntity());
        Either<ProblemDetail, Optional<L1Batch<SpendingEventEntity>>> expected = Either.right(Optional.empty());
        when(l1TransactionCreator.pullBlockchainTransaction("org1", unit)).thenReturn(expected);

        assertThat(publishable.buildL1Transaction("org1", unit)).isSameAs(expected);
    }

    @Test
    void findNotFinalizedYet_delegatesToGateway() {
        Limit limit = Limit.of(50);
        SpendingEventEntity entity = new SpendingEventEntity();
        when(repositoryGateway.findDispatchedEventsThatAreNotFinalizedYet("org1", limit)).thenReturn(Set.of(entity));

        assertThat(publishable.findNotFinalizedYet("org1", limit)).containsExactly(entity);
    }

    @Test
    void store_delegatesToGateway() {
        SpendingEventEntity entity = new SpendingEventEntity();

        publishable.store(entity);

        verify(repositoryGateway).store(entity);
    }

    @Test
    void storeAll_delegatesToGateway() {
        Set<SpendingEventEntity> entities = Set.of(new SpendingEventEntity());

        publishable.storeAll(entities);

        verify(repositoryGateway).storeAll(entities);
    }

    @Test
    void supportsLocking_returnsTrue() {
        assertThat(publishable.supportsLocking()).isTrue();
    }

    @Test
    void lock_delegatesToGateway() {
        Set<SpendingEventEntity> entities = Set.of(new SpendingEventEntity());

        publishable.lock(entities);

        verify(repositoryGateway).lock(entities);
    }

    @Test
    void unlock_delegatesToGateway() {
        Set<SpendingEventEntity> entities = Set.of(new SpendingEventEntity());

        publishable.unlock(entities);

        verify(repositoryGateway).unlock(entities);
    }

    @Test
    void notifyLedgerUpdate_sendsWithSpendingEventType() {
        Set<SpendingEventEntity> entities = Set.of(new SpendingEventEntity());

        publishable.notifyLedgerUpdate("org1", entities);

        verify(ledgerUpdatedEventPublisher).send("org1", LedgerUpdateType.SPENDING_EVENT, entities);
    }

}
