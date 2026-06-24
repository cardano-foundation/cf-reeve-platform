package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventPublishView;
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
                && e.getMilestone() != null
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
        event.setTxHash("tx-hash-abc");
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
        event.setMilestone(milestoneEntity("m1"));

        SpendingItemEntity item = spendingItemEntity(event);
        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of(item));
        when(allocationRepository.findById_EventId("e1")).thenReturn(List.of());

        SpendingEventView view = spendingEventService.toView(event);

        assertThat(view.getEventId()).isEqualTo("e1");
        assertThat(view.getEventType()).isEqualTo(EventType.SPENDING);
        assertThat(view.getStatus()).isEqualTo(EventStatus.DRAFT);
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
        event.setMilestone(null);

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(allocationRepository.findById_EventId("e1")).thenReturn(List.of());

        SpendingEventView view = spendingEventService.toView(event);

        assertThat(view.getMilestoneLabel()).isNull();
        verify(milestoneRepository, never()).findById(any());
    }

    @Test
    void publish_returnsLeft_whenAlreadyPublished() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(event));

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.publish("e1");

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("SPENDING_EVENT_ALREADY_PUBLISHED");
        verify(spendingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_spendingEvent_noMilestone_doesNotSaveMilestone() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(spendingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SpendingEventCreateRequest request = spendingCreateRequest(EventType.SPENDING, List.of(), List.of());
        // milestone is null — not set on request

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.create("p1", request);

        assertThat(result.isRight()).isTrue();
        verify(milestoneRepository, never()).saveAndFlush(any());
        verify(spendingEventRepository).saveAndFlush(argThat(e -> e.getMilestone() == null));
    }

    @Test
    void create_returnsLeft_whenMilestoneIdNotFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(milestoneRepository.findById("m-missing")).thenReturn(Optional.empty());

        SpendingEventCreateRequest request = spendingCreateRequest(EventType.SPENDING, List.of(), List.of());
        request.setMilestone(MilestoneCreateRequest.builder().milestoneId("m-missing").build());

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.create("p1", request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("MILESTONE_NOT_FOUND");
        verify(spendingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenMilestoneFieldsMissing() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));

        SpendingEventCreateRequest request = spendingCreateRequest(EventType.SPENDING, List.of(), List.of());
        // no milestoneId and missing required fields
        request.setMilestone(MilestoneCreateRequest.builder().build());

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.create("p1", request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("MILESTONE_FIELDS_REQUIRED");
        verify(spendingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void findByProjectIdAndFilter_delegatesToRepository() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        org.springframework.data.domain.Page<SpendingEventEntity> page =
                new org.springframework.data.domain.PageImpl<>(List.of());
        when(spendingEventRepository.findByProjectIdAndFilter("p1", EventStatus.DRAFT, EventType.SPENDING, pageable))
                .thenReturn(page);

        assertThat(spendingEventService.findByProjectIdAndFilter(
                "p1", Optional.of(EventStatus.DRAFT), Optional.of(EventType.SPENDING), pageable))
                .isEqualTo(page);
    }

    @Test
    void findByProjectIdAndFilter_passesNulls_whenFiltersEmpty() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        org.springframework.data.domain.Page<SpendingEventEntity> page =
                new org.springframework.data.domain.PageImpl<>(List.of());
        when(spendingEventRepository.findByProjectIdAndFilter("p1", null, null, pageable))
                .thenReturn(page);

        assertThat(spendingEventService.findByProjectIdAndFilter("p1", Optional.empty(), Optional.empty(), pageable))
                .isEqualTo(page);
    }

    @Test
    void toView_mapsAllScalarFields() {
        SpendingEventEntity event = SpendingEventEntity.builder()
                .id("e1")
                .eventType(EventType.SPENDING)
                .status(EventStatus.PUBLISHED)
                .fundingId("GRANT-001")
                .activityId("ACT-01")
                .currency("EUR")
                .totalAmount(new java.math.BigDecimal("777.00"))
                .txHash("tx-abc")
                .fundingTx("funding-tx-xyz")
                .project(projectEntity())
                .build();

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(allocationRepository.findById_EventId("e1")).thenReturn(List.of());

        SpendingEventView view = spendingEventService.toView(event);

        assertThat(view.getEventId()).isEqualTo("e1");
        assertThat(view.getProjectId()).isEqualTo("p1");
        assertThat(view.getEventType()).isEqualTo(EventType.SPENDING);
        assertThat(view.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(view.getFundingId()).isEqualTo("GRANT-001");
        assertThat(view.getActivityId()).isEqualTo("ACT-01");
        assertThat(view.getCurrency()).isEqualTo("EUR");
        assertThat(view.getTotalAmount()).isEqualByComparingTo("777.00");
        assertThat(view.getTxHash()).isEqualTo("tx-abc");
        assertThat(view.getFundingTx()).isEqualTo("funding-tx-xyz");
        assertThat(view.getMilestoneId()).isNull();
        assertThat(view.getMilestoneLabel()).isNull();
    }

    // --- update ---

    @Test
    void update_returnsLeft_whenSpendingEventIsPublished() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        SpendingEventEntity publishedEvent = spendingEventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(publishedEvent));

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.update("p1", "e1",
                spendingCreateRequest(EventType.SPENDING, List.of(), List.of()));

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("SPENDING_EVENT_ALREADY_PUBLISHED");
        assertThat(result.getLeft().getStatus()).isEqualTo(409);
        verify(spendingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returnsLeft_whenFundingEventIsPublished() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        SpendingEventEntity publishedEvent = spendingEventEntity(EventType.FUNDING, EventStatus.PUBLISHED);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(publishedEvent));

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.update("p1", "e1",
                spendingCreateRequest(EventType.FUNDING, List.of(), List.of()));

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("SPENDING_EVENT_ALREADY_PUBLISHED");
        assertThat(result.getLeft().getStatus()).isEqualTo(409);
        verify(spendingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returnsLeft_whenProjectNotFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.empty());

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.update("p1", "e1",
                spendingCreateRequest(EventType.SPENDING, List.of(), List.of()));

        assertThat(result.isLeft()).isTrue();
        verify(spendingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returnsLeft_whenEventNotFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.empty());

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.update("p1", "e1",
                spendingCreateRequest(EventType.SPENDING, List.of(), List.of()));

        assertThat(result.isLeft()).isTrue();
        verify(spendingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_spending_replacesExistingItemsWithRequestItems() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        SpendingEventEntity existingEvent = spendingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        existingEvent.getSpendingItems().add(spendingItemEntity(existingEvent)); // old item id="item-1"
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(existingEvent));
        when(spendingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SpendingEventCreateRequest request = spendingCreateRequest(EventType.SPENDING,
                List.of(spendingItemRequest(new BigDecimal("200.00")), spendingItemRequest(new BigDecimal("300.00"))),
                List.of());

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.update("p1", "e1", request);

        assertThat(result.isRight()).isTrue();
        verify(spendingEventRepository).saveAndFlush(argThat(e ->
                e.getSpendingItems().size() == 2
                && e.getSpendingItems().stream().noneMatch(i -> "item-1".equals(i.getId()))
                && e.getTotalAmount().compareTo(new BigDecimal("500.00")) == 0
        ));
    }

    @Test
    void update_spending_withEmptyRequestItems_clearsPreviousItems() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        SpendingEventEntity existingEvent = spendingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        existingEvent.getSpendingItems().add(spendingItemEntity(existingEvent));
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(existingEvent));
        when(spendingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.update("p1", "e1",
                spendingCreateRequest(EventType.SPENDING, List.of(), List.of()));

        assertThat(result.isRight()).isTrue();
        verify(spendingEventRepository).saveAndFlush(argThat(e ->
                e.getSpendingItems().isEmpty()
                && e.getTotalAmount().compareTo(BigDecimal.ZERO) == 0
        ));
    }

    @Test
    void update_spending_updatesScalarFieldsFromRequest() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));

        SpendingEventEntity existingEvent = spendingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(existingEvent));
        when(spendingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .eventType(EventType.SPENDING)
                .fundingId("NEW-GRANT")
                .activityId("NEW-ACTIVITY")
                .currency("EUR")
                .fundingTx("tx-new-hash")
                .spendingItems(List.of())
                .milestoneAllocations(List.of())
                .build();

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.update("p1", "e1", request);

        assertThat(result.isRight()).isTrue();
        verify(spendingEventRepository).saveAndFlush(argThat(e ->
                "NEW-GRANT".equals(e.getFundingId())
                && "NEW-ACTIVITY".equals(e.getActivityId())
                && "EUR".equals(e.getCurrency())
                && "tx-new-hash".equals(e.getFundingTx())
        ));
    }

    @Test
    void update_spending_createsNewMilestoneWhenNoIdProvided() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        SpendingEventEntity existingEvent = spendingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(existingEvent));

        MilestoneEntity created = milestoneEntity("m-new");
        when(milestoneRepository.saveAndFlush(any())).thenReturn(created);
        when(spendingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SpendingEventCreateRequest request = spendingCreateRequest(EventType.SPENDING, List.of(), List.of());
        request.setMilestone(milestoneCreateRequest());

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.update("p1", "e1", request);

        assertThat(result.isRight()).isTrue();
        verify(milestoneRepository).saveAndFlush(any());
        verify(spendingEventRepository).saveAndFlush(argThat(e -> e.getMilestone() != null && "m-new".equals(e.getMilestone().getId())));
    }

    @Test
    void update_spending_resolvesExistingMilestoneById() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        SpendingEventEntity existingEvent = spendingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(existingEvent));

        MilestoneEntity existing = milestoneEntity("m-existing");
        when(milestoneRepository.findById("m-existing")).thenReturn(Optional.of(existing));
        when(spendingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SpendingEventCreateRequest request = spendingCreateRequest(EventType.SPENDING, List.of(), List.of());
        request.setMilestone(MilestoneCreateRequest.builder().milestoneId("m-existing").build());

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.update("p1", "e1", request);

        assertThat(result.isRight()).isTrue();
        verify(milestoneRepository, never()).saveAndFlush(any());
        verify(spendingEventRepository).saveAndFlush(argThat(e -> e.getMilestone() != null && "m-existing".equals(e.getMilestone().getId())));
    }

    @Test
    void update_spending_returnsLeft_whenMilestoneIdNotFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));

        SpendingEventEntity existingEvent = spendingEventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(existingEvent));
        when(milestoneRepository.findById("m-missing")).thenReturn(Optional.empty());

        SpendingEventCreateRequest request = spendingCreateRequest(EventType.SPENDING, List.of(), List.of());
        request.setMilestone(MilestoneCreateRequest.builder().milestoneId("m-missing").build());

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.update("p1", "e1", request);

        assertThat(result.isLeft()).isTrue();
        verify(spendingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_funding_clearsMilestoneAllocationsAndRepopulates() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        SpendingEventEntity existingEvent = spendingEventEntity(EventType.FUNDING, EventStatus.DRAFT);
        MilestoneEntity oldMilestone = milestoneEntity("m-old");
        EventMilestoneAllocationEntity oldAlloc = EventMilestoneAllocationEntity.builder()
                .id(new EventMilestoneAllocationEntity.Id("e1", "m-old"))
                .allocatedAmount(new BigDecimal("10000.00"))
                .event(existingEvent)
                .milestone(oldMilestone)
                .build();
        existingEvent.getMilestoneAllocations().add(oldAlloc);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(existingEvent));

        MilestoneEntity newMilestone = milestoneEntity("m-new");
        when(milestoneRepository.saveAndFlush(any())).thenReturn(newMilestone);
        when(spendingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        EventMilestoneAllocationRequest newAlloc = EventMilestoneAllocationRequest.builder()
                .milestone(milestoneCreateRequest())
                .allocatedAmount(new BigDecimal("50000.00"))
                .build();
        SpendingEventCreateRequest request = spendingCreateRequest(EventType.FUNDING, List.of(), List.of(newAlloc));

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.update("p1", "e1", request);

        assertThat(result.isRight()).isTrue();
        verify(spendingEventRepository).saveAndFlush(argThat(e ->
                e.getMilestoneAllocations().size() == 1
                && e.getMilestoneAllocations().stream().noneMatch(a -> "m-old".equals(a.getId().getMilestoneId()))
                && e.getTotalAmount().compareTo(new BigDecimal("50000.00")) == 0
        ));
    }

    @Test
    void update_funding_flushesAfterClear() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        SpendingEventEntity existingEvent = spendingEventEntity(EventType.FUNDING, EventStatus.DRAFT);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(existingEvent));

        MilestoneEntity newMilestone = milestoneEntity("m-new");
        when(milestoneRepository.saveAndFlush(any())).thenReturn(newMilestone);
        when(spendingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SpendingEventCreateRequest request = spendingCreateRequest(EventType.FUNDING, List.of(),
                List.of(EventMilestoneAllocationRequest.builder()
                        .milestone(milestoneCreateRequest())
                        .allocatedAmount(new BigDecimal("20000.00"))
                        .build()));

        spendingEventService.update("p1", "e1", request);

        verify(spendingEventRepository).flush();
    }

    @Test
    void update_funding_returnsLeft_whenMilestoneIdNotFound() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        SpendingEventEntity existingEvent = spendingEventEntity(EventType.FUNDING, EventStatus.DRAFT);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(existingEvent));
        when(milestoneRepository.findById("m-missing")).thenReturn(Optional.empty());

        EventMilestoneAllocationRequest alloc = EventMilestoneAllocationRequest.builder()
                .milestone(MilestoneCreateRequest.builder().milestoneId("m-missing").build())
                .allocatedAmount(new BigDecimal("10000.00"))
                .build();
        SpendingEventCreateRequest request = spendingCreateRequest(EventType.FUNDING, List.of(), List.of(alloc));

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.update("p1", "e1", request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("MILESTONE_NOT_FOUND");
        verify(spendingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_funding_returnsLeft_whenMilestoneFieldsMissing() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        SpendingEventEntity existingEvent = spendingEventEntity(EventType.FUNDING, EventStatus.DRAFT);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(existingEvent));

        EventMilestoneAllocationRequest alloc = EventMilestoneAllocationRequest.builder()
                .milestone(MilestoneCreateRequest.builder().build()) // missing all required fields
                .allocatedAmount(new BigDecimal("10000.00"))
                .build();
        SpendingEventCreateRequest request = spendingCreateRequest(EventType.FUNDING, List.of(), List.of(alloc));

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.update("p1", "e1", request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("MILESTONE_FIELDS_REQUIRED");
        verify(spendingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_funding_existingMilestoneById_replacesAllocations() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        SpendingEventEntity existingEvent = spendingEventEntity(EventType.FUNDING, EventStatus.DRAFT);
        when(spendingEventRepository.findById("e1")).thenReturn(Optional.of(existingEvent));

        MilestoneEntity existing = milestoneEntity("m-existing");
        when(milestoneRepository.findById("m-existing")).thenReturn(Optional.of(existing));
        when(spendingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        EventMilestoneAllocationRequest alloc = EventMilestoneAllocationRequest.builder()
                .milestone(MilestoneCreateRequest.builder().milestoneId("m-existing").build())
                .allocatedAmount(new BigDecimal("30000.00"))
                .build();
        SpendingEventCreateRequest request = spendingCreateRequest(EventType.FUNDING, List.of(), List.of(alloc));

        Either<ProblemDetail, SpendingEventEntity> result = spendingEventService.update("p1", "e1", request);

        assertThat(result.isRight()).isTrue();
        verify(milestoneRepository, never()).saveAndFlush(any());
        verify(spendingEventRepository).saveAndFlush(argThat(e ->
                e.getMilestoneAllocations().size() == 1
                && "m-existing".equals(e.getMilestoneAllocations().get(0).getId().getMilestoneId())
        ));
    }

    // --- toPublishView ---

    @Test
    void toPublishView_spendingEvent_mapsAllScalarFieldsAndDate() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        event.setCreatedAt(LocalDateTime.of(2025, 6, 1, 10, 0));
        event.setFundingTx("ftx-1");
        event.setTotalAmount(new BigDecimal("300.00"));

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getEventId()).isEqualTo("e1");
        assertThat(view.getProjectId()).isEqualTo("p1");
        assertThat(view.getEventType()).isEqualTo(EventType.SPENDING);
        assertThat(view.getDate()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(view.getFundingId()).isEqualTo("GRANT-2025-001");
        assertThat(view.getActivityId()).isEqualTo("PROJ-AB");
        assertThat(view.getActivityTitle()).isEqualTo("Project AB");
        assertThat(view.getFundingTx()).isEqualTo("ftx-1");
        assertThat(view.getAmount()).isEqualByComparingTo("300.00");
        assertThat(view.getFundingDocHash()).isNull();
    }

    @Test
    void toPublishView_spendingEvent_withItemAndMilestone() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        event.setCreatedAt(LocalDateTime.of(2025, 6, 1, 10, 0));
        MilestoneEntity milestone = milestoneEntity("m1");
        event.setMilestone(milestone);

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of(spendingItemEntity(event)));
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getItems()).hasSize(1);
        assertThat(view.getMilestones()).hasSize(1);
        assertThat(view.getMilestones().get(0).getMilestoneId()).isEqualTo("m1");
        assertThat(view.getMilestones().get(0).getMilestoneLabel()).isEqualTo("Milestone AB");
        assertThat(view.getMilestones().get(0).getAllocatedAmount()).isNull();
        assertThat(view.getMilestones().get(0).getExpectedCost()).isEqualByComparingTo("50000.00");
    }

    @Test
    void toPublishView_spendingEvent_noMilestone_returnsEmptyMilestones() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        event.setCreatedAt(LocalDateTime.of(2025, 6, 1, 10, 0));
        event.setMilestone(null);

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getMilestones()).isEmpty();
        verify(milestoneRepository, never()).findById(any());
    }

    @Test
    void toPublishView_spendingEvent_milestoneNotFoundInRepo_returnsEmptyMilestones() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        event.setCreatedAt(LocalDateTime.of(2025, 6, 1, 10, 0));
        event.setMilestone(milestoneEntity("m-gone"));

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(milestoneRepository.findById("m-gone")).thenReturn(Optional.empty());

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getMilestones()).isEmpty();
    }

    @Test
    void toPublishView_fundingEvent_allocationWithFoundMilestone() {
        SpendingEventEntity event = spendingEventEntity(EventType.FUNDING, EventStatus.PUBLISHED);
        event.setCreatedAt(LocalDateTime.of(2025, 6, 1, 10, 0));
        MilestoneEntity milestone = milestoneEntity("m1");
        EventMilestoneAllocationEntity alloc = EventMilestoneAllocationEntity.builder()
                .id(new EventMilestoneAllocationEntity.Id("e1", "m1"))
                .allocatedAmount(new BigDecimal("50000.00"))
                .event(event)
                .build();

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(allocationRepository.findById_EventId("e1")).thenReturn(List.of(alloc));
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getMilestones()).hasSize(1);
        assertThat(view.getMilestones().get(0).getMilestoneId()).isEqualTo("m1");
        assertThat(view.getMilestones().get(0).getMilestoneLabel()).isEqualTo("Milestone AB");
        assertThat(view.getMilestones().get(0).getAllocatedAmount()).isEqualByComparingTo("50000.00");
        assertThat(view.getMilestones().get(0).getCurrency()).isNotNull();
        assertThat(view.getMilestones().get(0).getDueDate()).isEqualTo(LocalDate.of(2025, 6, 30));
        assertThat(view.getItems()).isEmpty();
    }

    @Test
    void toPublishView_fundingEvent_allocationMilestoneNotFound_nullsForMilestoneFields() {
        SpendingEventEntity event = spendingEventEntity(EventType.FUNDING, EventStatus.PUBLISHED);
        event.setCreatedAt(LocalDateTime.of(2025, 6, 1, 10, 0));
        EventMilestoneAllocationEntity alloc = EventMilestoneAllocationEntity.builder()
                .id(new EventMilestoneAllocationEntity.Id("e1", "m-missing"))
                .allocatedAmount(new BigDecimal("10000.00"))
                .event(event)
                .build();

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(allocationRepository.findById_EventId("e1")).thenReturn(List.of(alloc));
        when(milestoneRepository.findById("m-missing")).thenReturn(Optional.empty());

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getMilestones()).hasSize(1);
        SpendingEventPublishView.Milestone m = view.getMilestones().get(0);
        assertThat(m.getMilestoneId()).isEqualTo("m-missing");
        assertThat(m.getMilestoneLabel()).isNull();
        assertThat(m.getExpectedCost()).isNull();
        assertThat(m.getCurrency()).isNull();
        assertThat(m.getDueDate()).isNull();
        assertThat(m.getAllocatedAmount()).isEqualByComparingTo("10000.00");
    }

    @Test
    void toPublishView_currencyWithIsoPrefix_extractsCustCode() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        event.setCreatedAt(LocalDateTime.of(2025, 6, 1, 10, 0));
        event.setCurrency("ISO_4217:CHF");

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getCurrency().getId()).isEqualTo("ISO_4217:CHF");
        assertThat(view.getCurrency().getCustCode()).isEqualTo("CHF");
    }

    @Test
    void toPublishView_currencyPlainCode_buildsIsoId() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        event.setCreatedAt(LocalDateTime.of(2025, 6, 1, 10, 0));
        // helper sets currency to "USD" (plain)

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getCurrency().getId()).isEqualTo("ISO_4217:USD");
        assertThat(view.getCurrency().getCustCode()).isEqualTo("USD");
    }

    @Test
    void toPublishView_nullCurrency_returnsNullCurrencyField() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        event.setCreatedAt(LocalDateTime.of(2025, 6, 1, 10, 0));
        event.setCurrency(null);

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getCurrency()).isNull();
    }

    @Test
    void toPublishView_spendingEvent_itemFieldsAreMapped() {
        SpendingEventEntity event = spendingEventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        event.setCreatedAt(LocalDateTime.of(2025, 6, 1, 10, 0));

        SpendingItemEntity item = SpendingItemEntity.builder()
                .id("item-x")
                .category("Equipment")
                .vendor("Vendor XY")
                .amountFcy(new BigDecimal("250.00"))
                .currency("EUR")
                .fxRate(new BigDecimal("1.05"))
                .amountRcy(new BigDecimal("262.50"))
                .spendDate(LocalDate.of(2025, 5, 15))
                .hash("hash-x")
                .notes("note-x")
                .event(event)
                .build();

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of(item));

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getItems()).hasSize(1);
        SpendingEventPublishView.SpendItem i = view.getItems().get(0);
        assertThat(i.getItemId()).isEqualTo("item-x");
        assertThat(i.getCategory()).isEqualTo("Equipment");
        assertThat(i.getVendor()).isEqualTo("Vendor XY");
        assertThat(i.getAmountFcy()).isEqualByComparingTo("250.00");
        assertThat(i.getFxRate()).isEqualByComparingTo("1.05");
        assertThat(i.getAmountRcy()).isEqualByComparingTo("262.50");
        assertThat(i.getSpendDate()).isEqualTo(LocalDate.of(2025, 5, 15));
        assertThat(i.getDocumentHash()).isEqualTo("hash-x");
        assertThat(i.getNotes()).isEqualTo("note-x");
        assertThat(i.getCurrency().getCustCode()).isEqualTo("EUR");
        assertThat(i.getCurrency().getId()).isEqualTo("ISO_4217:EUR");
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
