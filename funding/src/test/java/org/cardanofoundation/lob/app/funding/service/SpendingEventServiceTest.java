package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.funding.domain.entity.*;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.EventMilestoneAllocationRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingEventCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingItemRequest;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.repository.*;

@ExtendWith(MockitoExtension.class)
class SpendingEventServiceTest {

    @Mock
    private SpendingEventRepository spendingEventRepository;
    @Mock
    private FundingProjectRepository projectRepository;
    @Mock
    private MilestoneRepository milestoneRepository;
    @Mock
    private SpendingItemRepository spendingItemRepository;
    @Mock
    private EventMilestoneAllocationRepository allocationRepository;

    @InjectMocks
    private SpendingEventService spendingEventService;

    @Test
    void findById_delegatesToRepository() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(event));

        assertThat(spendingEventService.findById("e1")).contains(event);
    }

    @Test
    void findById_returnsEmpty_whenNotFound() {
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.empty());

        assertThat(spendingEventService.findById("e1")).isEmpty();
    }

    @Test
    void findByProjectId_returnsList() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventRepository.findByProject_Id("p1")).thenReturn(List.of(event));

        assertThat(spendingEventService.findByProjectId("p1")).containsExactly(event);
    }

    @Test
    void create_returnsEmpty_whenProjectNotFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.empty());

        SpendingEventCreateRequest request = spendingCreateRequest(EventType.SPENDING, List.of(), List.of());
        assertThat(spendingEventService.create("p1", request).isLeft()).isTrue();
        verify(spendingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_spendingEvent_populatesItemsAndCalculatesTotal() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        SpendingItemRequest item1 = spendingItemRequest(new BigDecimal("100.00"));
        SpendingItemRequest item2 = spendingItemRequest(new BigDecimal("200.00"));

        SpendingEventCreateRequest request = spendingCreateRequest(EventType.SPENDING, List.of(item1, item2), List.of());
        request.setMilestone(milestoneCreateRequest());

        when(milestoneRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        SpendingEventEntity saved = spendingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventRepository.saveAndFlush(any())).thenReturn(saved);

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.create("p1", request);

        assertThat(result.isRight()).isTrue();
        verify(milestoneRepository).saveAndFlush(any());
        verify(spendingEventRepository).saveAndFlush(argThat(e ->
                e.getEventType() == EventType.SPENDING
                && e.getStatus() == EventStatus.DRAFT
                && e.getSpendingItems().size() == 2
                && e.getTotalAmount().compareTo(new BigDecimal("300.00")) == 0
                && e.getMilestoneId() != null
        ));
    }

    @Test
    void create_spendingEvent_populatesMilestoneAllocationsAndCalculatesTotal() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        when(milestoneRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        EventMilestoneAllocationRequest alloc = EventMilestoneAllocationRequest.builder()
                .milestone(milestoneCreateRequest())
                .allocatedAmount(new BigDecimal("50000.00"))
                .build();

        SpendingEventCreateRequest request = spendingCreateRequest(EventType.FUNDING, List.of(), List.of(alloc));

        SpendingEventEntity saved = spendingEventEntity(EventType.FUNDING, EventStatus.DRAFT);
        when(spendingEventRepository.saveAndFlush(any())).thenReturn(saved);

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.create("p1", request);

        assertThat(result.isRight()).isTrue();
        verify(spendingEventRepository).saveAndFlush(argThat(e ->
                e.getEventType() == EventType.FUNDING
                && e.getMilestoneAllocations().size() == 1
                && e.getTotalAmount().compareTo(new BigDecimal("50000.00")) == 0
        ));
    }

    @Test
    void create_spendingEvent_withNullAllocatedAmount_totalIsZero() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        when(milestoneRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        EventMilestoneAllocationRequest alloc = EventMilestoneAllocationRequest.builder()
                .milestone(milestoneCreateRequest())
                .allocatedAmount(null)
                .build();

        SpendingEventCreateRequest request = spendingCreateRequest(EventType.FUNDING, List.of(), List.of(alloc));

        SpendingEventEntity saved = spendingEventEntity(EventType.FUNDING, EventStatus.DRAFT);
        when(spendingEventRepository.saveAndFlush(any())).thenReturn(saved);

        spendingEventService.create("p1", request);

        verify(spendingEventRepository).saveAndFlush(argThat(e ->
                e.getTotalAmount().compareTo(BigDecimal.ZERO) == 0
        ));
    }

    @Test
    void publish_setsStatusAndDispatchApproved() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(event));
        when(spendingEventRepository.saveAndFlush(event)).thenReturn(event);

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.publish("e1");

        assertThat(result.isRight()).isTrue();
        assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void publish_returnsEmpty_whenEventNotFound() {
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.empty());

        assertThat(spendingEventService.publish("e1").isLeft()).isTrue();
    }

    @Test
    void delete_returnsFalse_whenNotFound() {
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.empty());

        assertThat(spendingEventService.delete("e1").isLeft()).isTrue();
        verify(spendingEventRepository, never()).delete(any());
    }

    @Test
    void delete_returnsFalse_whenEventIsPublished() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(event));

        assertThat(spendingEventService.delete("e1").isLeft()).isTrue();
        verify(spendingEventRepository, never()).delete(any());
    }

    @Test
    void delete_deletesAndReturnsTrue_forDraftEvent() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(event));

        assertThat(spendingEventService.delete("e1").isRight()).isTrue();
        verify(spendingEventRepository).delete(event);
    }

    @Test
    void toView_mapsEventWithSpendingItems() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        event.setId("e1");
        event.setMilestoneId("m1");

        SpendingItemEntity item = spendingItemEntity(event);
        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of(item));
        when(allocationRepository.findById_EventId("e1")).thenReturn(List.of());

        MilestoneEntity milestone = milestoneEntity("m1");
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));

        SpendingEventView view = spendingEventService.toView(event);

        assertThat(view.getEventId()).isEqualTo("e1");
        assertThat(view.getEventType()).isEqualTo(EventType.SPENDING);
        assertThat(view.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(view.getMilestoneLabel()).isEqualTo("Milestone AB");
        assertThat(view.getSpendingItems()).hasSize(1);
        assertThat(view.getMilestoneAllocations()).isEmpty();
    }

    @Test
    void toView_mapsEventWithMilestoneAllocations() {
        SpendingEventEntity event = spendingEventEntity(EventType.FUNDING, EventStatus.DRAFT);
        event.setId("e1");

        MilestoneEntity milestone = milestoneEntity("m1");
        EventMilestoneAllocationEntity.Id allocId = new EventMilestoneAllocationEntity.Id("e1", "m1");
        EventMilestoneAllocationEntity alloc = EventMilestoneAllocationEntity.builder()
                .id(allocId)
                .allocatedAmount(new BigDecimal("50000.00"))
                .event(event)
                .milestone(milestone)
                .build();

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(allocationRepository.findById_EventId("e1")).thenReturn(List.of(alloc));
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));

        SpendingEventView view = spendingEventService.toView(event);

        assertThat(view.getSpendingItems()).isEmpty();
        assertThat(view.getMilestoneAllocations()).hasSize(1);
        assertThat(view.getMilestoneAllocations().get(0).getMilestoneId()).isEqualTo("m1");
        assertThat(view.getMilestoneAllocations().get(0).getAllocatedAmount()).isEqualByComparingTo("50000.00");
        assertThat(view.getMilestoneAllocations().get(0).getMilestoneLabel()).isEqualTo("Milestone AB");
    }

    @Test
    void toView_handlesNullMilestoneId() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        event.setId("e1");
        event.setMilestoneId(null);

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(allocationRepository.findById_EventId("e1")).thenReturn(List.of());

        SpendingEventView view = spendingEventService.toView(event);

        assertThat(view.getMilestoneLabel()).isNull();
        verify(milestoneRepository, never()).findById(any());
    }

    // --- helpers ---

    private SpendingEventEntity spendingEventEntity(EventType type, EventStatus status) {
        return SpendingEventEntity.builder()
                .id("e1")
                .eventType(type)
                .status(status)
                .fundingId("GRANT-2025-001")
                .activityId("PROJ-AB")
                .currency("USD")
                .totalAmount(BigDecimal.ZERO)
                .project(projectEntity())
                .build();
    }

    private ProjectEntity projectEntity() {
        return ProjectEntity.builder()
                .id("p1")
                .organisationId("org1")
                .fundingId("GRANT-2025-001")
                .activityId("PROJ-AB")
                .activityTitle("Project AB")
                .expectedTotalAmount(new BigDecimal("200000.00"))
                .currency("USD")
                .build();
    }

    private MilestoneEntity milestoneEntity(String id) {
        return MilestoneEntity.builder()
                .id(id)
                .label("Milestone AB")
                .expectedCost(new BigDecimal("50000.00"))
                .currency("USD")
                .dueDate(LocalDate.of(2025, 6, 30))
                .project(projectEntity())
                .build();
    }

    private SpendingItemEntity spendingItemEntity(SpendingEventEntity event) {
        return SpendingItemEntity.builder()
                .id("item-1")
                .category("Personnel")
                .vendor("Vendor AB")
                .amountFcy(new BigDecimal("100.00"))
                .currency("USD")
                .spendDate(LocalDate.of(2025, 4, 3))
                .event(event)
                .build();
    }

    private SpendingItemRequest spendingItemRequest(BigDecimal amount) {
        return SpendingItemRequest.builder()
                .category("Personnel")
                .vendor("Vendor AB")
                .amountFcy(amount)
                .currency("USD")
                .spendDate(LocalDate.of(2025, 4, 3))
                .build();
    }

    private SpendingEventCreateRequest spendingCreateRequest(
            EventType type,
            List<SpendingItemRequest> items,
            List<EventMilestoneAllocationRequest> allocations) {
        return SpendingEventCreateRequest.builder()
                .eventType(type)
                .fundingId("GRANT-2025-001")
                .activityId("PROJ-AB")
                .currency("USD")
                .spendingItems(items)
                .milestoneAllocations(allocations)
                .build();
    }

    private MilestoneCreateRequest milestoneCreateRequest() {
        return MilestoneCreateRequest.builder()
                .label("Milestone AB")
                .expectedCost(new BigDecimal("50000.00"))
                .currency("USD")
                .dueDate(LocalDate.of(2025, 6, 30))
                .build();
    }

}
