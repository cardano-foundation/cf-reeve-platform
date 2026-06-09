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
import org.cardanofoundation.lob.app.funding.domain.request.FundingEventCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.FundingItemRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.FundingEventView;
import org.cardanofoundation.lob.app.funding.repository.*;

@ExtendWith(MockitoExtension.class)
class FundingEventServiceTest {

    @Mock
    private FundingEventRepository fundingEventRepository;
    @Mock
    private FundingProjectRepository projectRepository;
    @Mock
    private MilestoneRepository milestoneRepository;
    @Mock
    private FundingItemRepository fundingItemRepository;
    @Mock
    private EventMilestoneAllocationRepository allocationRepository;

    @InjectMocks
    private FundingEventService fundingEventService;

    @Test
    void findById_delegatesToRepository() {
        FundingEventEntity event = fundingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(event));

        assertThat(fundingEventService.findById("e1")).contains(event);
    }

    @Test
    void findById_returnsEmpty_whenNotFound() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.empty());

        assertThat(fundingEventService.findById("e1")).isEmpty();
    }

    @Test
    void findByProjectId_returnsList() {
        FundingEventEntity event = fundingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(fundingEventRepository.findByProject_Id("p1")).thenReturn(List.of(event));

        assertThat(fundingEventService.findByProjectId("p1")).containsExactly(event);
    }

    @Test
    void create_returnsEmpty_whenProjectNotFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.empty());

        FundingEventCreateRequest request = fundingCreateRequest(EventType.SPENDING, List.of(), List.of());
        assertThat(fundingEventService.create("p1", request).isLeft()).isTrue();
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_fundingEvent_populatesItemsAndCalculatesTotal() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        FundingItemRequest item1 = fundingItemRequest(new BigDecimal("100.00"));
        FundingItemRequest item2 = fundingItemRequest(new BigDecimal("200.00"));

        FundingEventCreateRequest request = fundingCreateRequest(EventType.SPENDING, List.of(item1, item2), List.of());
        request.setMilestone(milestoneCreateRequest());

        when(milestoneRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        FundingEventEntity saved = fundingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(fundingEventRepository.saveAndFlush(any())).thenReturn(saved);

        Either<ProblemDetail, FundingEventEntity> result = fundingEventService.create("p1", request);

        assertThat(result.isRight()).isTrue();
        verify(milestoneRepository).saveAndFlush(any());
        verify(fundingEventRepository).saveAndFlush(argThat(e ->
                e.getEventType() == EventType.SPENDING
                && e.getStatus() == EventStatus.DRAFT
                && e.getFundingItems().size() == 2
                && e.getTotalAmount().compareTo(new BigDecimal("300.00")) == 0
                && e.getMilestoneId() != null
        ));
    }

    @Test
    void create_fundingEvent_populatesMilestoneAllocationsAndCalculatesTotal() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        when(milestoneRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        EventMilestoneAllocationRequest alloc = EventMilestoneAllocationRequest.builder()
                .milestone(milestoneCreateRequest())
                .allocatedAmount(new BigDecimal("50000.00"))
                .build();

        FundingEventCreateRequest request = fundingCreateRequest(EventType.FUNDING, List.of(), List.of(alloc));

        FundingEventEntity saved = fundingEventEntity(EventType.FUNDING, EventStatus.DRAFT);
        when(fundingEventRepository.saveAndFlush(any())).thenReturn(saved);

        Either<ProblemDetail, FundingEventEntity> result = fundingEventService.create("p1", request);

        assertThat(result.isRight()).isTrue();
        verify(fundingEventRepository).saveAndFlush(argThat(e ->
                e.getEventType() == EventType.FUNDING
                && e.getMilestoneAllocations().size() == 1
                && e.getTotalAmount().compareTo(new BigDecimal("50000.00")) == 0
        ));
    }

    @Test
    void create_fundingEvent_withNullAllocatedAmount_totalIsZero() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        when(milestoneRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        EventMilestoneAllocationRequest alloc = EventMilestoneAllocationRequest.builder()
                .milestone(milestoneCreateRequest())
                .allocatedAmount(null)
                .build();

        FundingEventCreateRequest request = fundingCreateRequest(EventType.FUNDING, List.of(), List.of(alloc));

        FundingEventEntity saved = fundingEventEntity(EventType.FUNDING, EventStatus.DRAFT);
        when(fundingEventRepository.saveAndFlush(any())).thenReturn(saved);

        fundingEventService.create("p1", request);

        verify(fundingEventRepository).saveAndFlush(argThat(e ->
                e.getTotalAmount().compareTo(BigDecimal.ZERO) == 0
        ));
    }

    @Test
    void publish_setsStatusAndTxHash() {
        FundingEventEntity event = fundingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(event));
        when(fundingEventRepository.saveAndFlush(event)).thenReturn(event);

        Either<ProblemDetail, FundingEventEntity> result = fundingEventService.publish("e1");

        assertThat(result.isRight()).isTrue();
        assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void publish_returnsEmpty_whenEventNotFound() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.empty());

        assertThat(fundingEventService.publish("e1").isLeft()).isTrue();
    }

    @Test
    void delete_returnsFalse_whenNotFound() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.empty());

        assertThat(fundingEventService.delete("e1").isLeft()).isTrue();
        verify(fundingEventRepository, never()).delete(any());
    }

    @Test
    void delete_returnsFalse_whenEventIsPublished() {
        FundingEventEntity event = fundingEventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(event));

        assertThat(fundingEventService.delete("e1").isLeft()).isTrue();
        verify(fundingEventRepository, never()).delete(any());
    }

    @Test
    void delete_deletesAndReturnsTrue_forDraftEvent() {
        FundingEventEntity event = fundingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(event));

        assertThat(fundingEventService.delete("e1").isRight()).isTrue();
        verify(fundingEventRepository).delete(event);
    }

    @Test
    void toView_mapsEventWithFundingItems() {
        FundingEventEntity event = fundingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        event.setId("e1");
        event.setMilestoneId("m1");

        FundingItemEntity item = fundingItemEntity(event);
        when(fundingItemRepository.findByEvent_Id("e1")).thenReturn(List.of(item));
        when(allocationRepository.findById_EventId("e1")).thenReturn(List.of());

        MilestoneEntity milestone = milestoneEntity("m1");
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));

        FundingEventView view = fundingEventService.toView(event);

        assertThat(view.getEventId()).isEqualTo("e1");
        assertThat(view.getEventType()).isEqualTo(EventType.SPENDING);
        assertThat(view.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(view.getMilestoneLabel()).isEqualTo("Milestone AB");
        assertThat(view.getFundingItems()).hasSize(1);
        assertThat(view.getMilestoneAllocations()).isEmpty();
    }

    @Test
    void toView_mapsEventWithMilestoneAllocations() {
        FundingEventEntity event = fundingEventEntity(EventType.FUNDING, EventStatus.DRAFT);
        event.setId("e1");

        MilestoneEntity milestone = milestoneEntity("m1");
        EventMilestoneAllocationEntity.Id allocId = new EventMilestoneAllocationEntity.Id("e1", "m1");
        EventMilestoneAllocationEntity alloc = EventMilestoneAllocationEntity.builder()
                .id(allocId)
                .allocatedAmount(new BigDecimal("50000.00"))
                .event(event)
                .milestone(milestone)
                .build();

        when(fundingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(allocationRepository.findById_EventId("e1")).thenReturn(List.of(alloc));
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));

        FundingEventView view = fundingEventService.toView(event);

        assertThat(view.getFundingItems()).isEmpty();
        assertThat(view.getMilestoneAllocations()).hasSize(1);
        assertThat(view.getMilestoneAllocations().get(0).getMilestoneId()).isEqualTo("m1");
        assertThat(view.getMilestoneAllocations().get(0).getAllocatedAmount()).isEqualByComparingTo("50000.00");
        assertThat(view.getMilestoneAllocations().get(0).getMilestoneLabel()).isEqualTo("Milestone AB");
    }

    @Test
    void toView_handlesNullMilestoneId() {
        FundingEventEntity event = fundingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        event.setId("e1");
        event.setMilestoneId(null);

        when(fundingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(allocationRepository.findById_EventId("e1")).thenReturn(List.of());

        FundingEventView view = fundingEventService.toView(event);

        assertThat(view.getMilestoneLabel()).isNull();
        verify(milestoneRepository, never()).findById(any());
    }

    // --- helpers ---

    private FundingEventEntity fundingEventEntity(EventType type, EventStatus status) {
        return FundingEventEntity.builder()
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

    private FundingItemEntity fundingItemEntity(FundingEventEntity event) {
        return FundingItemEntity.builder()
                .id("item-1")
                .category("Personnel")
                .vendor("Vendor AB")
                .amountFcy(new BigDecimal("100.00"))
                .currency("USD")
                .spendDate(LocalDate.of(2025, 4, 3))
                .event(event)
                .build();
    }

    private FundingItemRequest fundingItemRequest(BigDecimal amount) {
        return FundingItemRequest.builder()
                .category("Personnel")
                .vendor("Vendor AB")
                .amountFcy(amount)
                .currency("USD")
                .spendDate(LocalDate.of(2025, 4, 3))
                .build();
    }

    private FundingEventCreateRequest fundingCreateRequest(
            EventType type,
            List<FundingItemRequest> items,
            List<EventMilestoneAllocationRequest> allocations) {
        return FundingEventCreateRequest.builder()
                .eventType(type)
                .fundingId("GRANT-2025-001")
                .activityId("PROJ-AB")
                .currency("USD")
                .fundingItems(items)
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
