package org.cardanofoundation.lob.app.funding.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.events.SpendingEventsPublishCommand;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventPublishView;
import org.cardanofoundation.lob.app.funding.repository.FundingEventRepository;
import org.cardanofoundation.lob.app.funding.service.SpendingEventService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@ExtendWith(MockitoExtension.class)
class EventPublishJobTest {

    @Mock
    private FundingEventRepository fundingEventRepository;
    @Mock
    private SpendingEventService spendingEventService;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private OrganisationPublicApi organisationPublicApi;

    @InjectMocks
    private EventPublishJob eventPublishJob;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(eventPublishJob, "dispatchBatchSize", 100);
    }

    @Test
    void publishEvents_noOrganisations_doesNothing() {
        when(organisationPublicApi.listAll()).thenReturn(List.of());

        eventPublishJob.publishEvents();

        verify(fundingEventRepository, never()).findAllToBePublished(any());
        verify(applicationEventPublisher, never()).publishEvent(any());
        verify(fundingEventRepository, never()).saveAll(any());
    }

    @Test
    void publishEvents_noPublishableEvents_doesNothing() {
        Organisation org = mock(Organisation.class);
        when(org.getId()).thenReturn("org1");
        when(organisationPublicApi.listAll()).thenReturn(List.of(org));
        when(fundingEventRepository.findAllToBePublished("org1")).thenReturn(Set.of());

        eventPublishJob.publishEvents();

        verify(applicationEventPublisher, never()).publishEvent(any());
        verify(fundingEventRepository).saveAll(Set.of());
    }

    @Test
    void publishEvents_publishesCommandAndUpdatesDispatchStatus() {
        Organisation org = mock(Organisation.class);
        when(org.getId()).thenReturn("org1");
        when(organisationPublicApi.listAll()).thenReturn(List.of(org));

        FundingEventEntity event = FundingEventEntity.builder()
                .id("e1").eventType(EventType.SPENDING).status(EventStatus.PUBLISHED)
                .organisationId("org1").fundingId("GRANT-001").currency("USD")
                .totalAmount(BigDecimal.ZERO).build();
        event.setCreatedAt(LocalDateTime.now());

        SpendingEventPublishView publishView = SpendingEventPublishView.builder()
                .eventId("e1").organisationId("org1").eventType(EventType.SPENDING)
                .date(LocalDate.now()).fundingId("GRANT-001")
                .amount(BigDecimal.ZERO).projectAllocations(List.of()).items(List.of()).build();

        when(fundingEventRepository.findAllToBePublished("org1")).thenReturn(Set.of(event));
        when(spendingEventService.toPublishView(event)).thenReturn(publishView);

        eventPublishJob.publishEvents();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        SpendingEventsPublishCommand command = (SpendingEventsPublishCommand) captor.getValue();
        assertThat(command.getOrganisationId()).isEqualTo("org1");
        assertThat(command.getSpendingEvents()).contains(publishView);
        assertThat(event.getLedgerDispatchStatus()).isEqualTo(LedgerDispatchStatus.DISPATCHED);
        assertThat(event.getPublishedAt()).isNotNull();
        verify(fundingEventRepository).saveAll(Set.of(event));
    }

    @Test
    void publishEvents_multipleOrganisations_processedIndependently() {
        Organisation org1 = mock(Organisation.class);
        Organisation org2 = mock(Organisation.class);
        when(org1.getId()).thenReturn("org1");
        when(org2.getId()).thenReturn("org2");
        when(organisationPublicApi.listAll()).thenReturn(List.of(org1, org2));
        when(fundingEventRepository.findAllToBePublished("org1")).thenReturn(Set.of());
        when(fundingEventRepository.findAllToBePublished("org2")).thenReturn(Set.of());

        eventPublishJob.publishEvents();

        verify(fundingEventRepository).findAllToBePublished("org1");
        verify(fundingEventRepository).findAllToBePublished("org2");
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void publishEvents_batchesEventsWhenExceedingBatchSize() {
        ReflectionTestUtils.setField(eventPublishJob, "dispatchBatchSize", 1);

        Organisation org = mock(Organisation.class);
        when(org.getId()).thenReturn("org1");
        when(organisationPublicApi.listAll()).thenReturn(List.of(org));

        FundingEventEntity event1 = buildEvent("e1");
        FundingEventEntity event2 = buildEvent("e2");
        SpendingEventPublishView view1 = buildPublishView("e1");
        SpendingEventPublishView view2 = buildPublishView("e2");

        when(fundingEventRepository.findAllToBePublished("org1")).thenReturn(Set.of(event1, event2));
        when(spendingEventService.toPublishView(event1)).thenReturn(view1);
        when(spendingEventService.toPublishView(event2)).thenReturn(view2);

        eventPublishJob.publishEvents();

        // batch size 1 means two separate publish commands
        verify(applicationEventPublisher, times(2)).publishEvent(any(SpendingEventsPublishCommand.class));
    }

    // --- helpers ---

    private FundingEventEntity buildEvent(String id) {
        FundingEventEntity event = FundingEventEntity.builder()
                .id(id).eventType(EventType.SPENDING).status(EventStatus.PUBLISHED)
                .organisationId("org1").fundingId("GRANT-001").currency("USD")
                .totalAmount(BigDecimal.ZERO).build();
        event.setCreatedAt(LocalDateTime.now());
        return event;
    }

    private SpendingEventPublishView buildPublishView(String eventId) {
        return SpendingEventPublishView.builder()
                .eventId(eventId).organisationId("org1").eventType(EventType.SPENDING)
                .date(LocalDate.now()).fundingId("GRANT-001")
                .amount(BigDecimal.ZERO).projectAllocations(List.of()).items(List.of()).build();
    }

}
