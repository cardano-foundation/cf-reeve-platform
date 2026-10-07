package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;

import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingEventEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;

@ExtendWith(MockitoExtension.class)
class SpendingEventEntityRepositoryGatewayTest {

    @Mock
    private SpendingEventEntityRepository repository;

    @Mock
    private EntityManager entityManager;

    private SpendingEventEntityRepositoryGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new SpendingEventEntityRepositoryGateway(repository, Clock.systemUTC(), entityManager);
    }

    private static SpendingEventEntity event(String id, BlockchainPublishStatus status, String vendor) {
        return SpendingEventEntity.builder()
                .eventId(id)
                .vendor(vendor)
                .l1SubmissionData(L1SubmissionData.builder().publishStatus(status).build())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Set<SpendingEventEntity> savedEntities() {
        ArgumentCaptor<Iterable<SpendingEventEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        Set<SpendingEventEntity> saved = new java.util.HashSet<>();
        captor.getValue().forEach(saved::add);
        return saved;
    }

    @Test
    void storeOnlyNew_newEvent_isStored() {
        SpendingEventEntity incoming = event("e1", BlockchainPublishStatus.STORED, "Vendor");
        when(repository.findAllById(Set.of("e1"))).thenReturn(List.of());
        when(repository.saveAll(any())).thenAnswer(invocation -> List.copyOf(Set.of(incoming)));

        gateway.storeOnlyNew(Set.of(incoming));

        assertThat(savedEntities()).containsExactly(incoming);
        verify(entityManager, never()).remove(any());
    }

    @Test
    void storeOnlyNew_eventInFlight_isKeptAsIs() {
        SpendingEventEntity existing = event("e1", BlockchainPublishStatus.SUBMITTED, "Old vendor");
        SpendingEventEntity incoming = event("e1", BlockchainPublishStatus.STORED, "New vendor");
        when(repository.findAllById(Set.of("e1"))).thenReturn(List.of(existing));
        when(repository.saveAll(any())).thenAnswer(invocation -> List.of());

        Set<SpendingEventEntity> result = gateway.storeOnlyNew(Set.of(incoming));

        assertThat(savedEntities()).isEmpty();
        assertThat(result).singleElement().extracting(SpendingEventEntity::getVendor).isEqualTo("Old vendor");
        verify(entityManager, never()).remove(any());
    }

    @Test
    void storeOnlyNew_failedEventPublishedAgain_replacesTheStoredCopy() {
        SpendingEventEntity failed = event("e1", BlockchainPublishStatus.ERROR, "Old vendor");
        SpendingEventEntity corrected = event("e1", BlockchainPublishStatus.STORED, "Corrected vendor");
        when(repository.findAllById(Set.of("e1"))).thenReturn(List.of(failed));
        when(repository.saveAll(any())).thenAnswer(invocation -> List.of(corrected));

        Set<SpendingEventEntity> result = gateway.storeOnlyNew(Set.of(corrected));

        InOrder inOrder = inOrder(entityManager, repository);
        inOrder.verify(entityManager).remove(failed);
        inOrder.verify(entityManager).flush();
        inOrder.verify(repository).saveAll(any());
        assertThat(savedEntities()).singleElement().extracting(SpendingEventEntity::getVendor).isEqualTo("Corrected vendor");
        assertThat(result).singleElement().extracting(SpendingEventEntity::getVendor).isEqualTo("Corrected vendor");
    }

}
