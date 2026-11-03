package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.funding.domain.entity.EventMilestoneAllocationEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingEventRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;

@ExtendWith(MockitoExtension.class)
class FundingCascadeDeleteServiceTest {

    @Mock
    private FundingProjectRepository projectRepository;
    @Mock
    private MilestoneRepository milestoneRepository;
    @Mock
    private EventMilestoneAllocationRepository allocationRepository;
    @Mock
    private FundingEventRepository fundingEventRepository;

    @InjectMocks
    private FundingCascadeDeleteService service;

    // --- deleteMilestone ---

    @Test
    void deleteMilestone_blocks_whenLinkedToPublishedEvent() {
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(true);

        Either<ProblemDetail, List<FundingEventEntity>> result = service.deleteMilestone(milestone("m1"));

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
        assertThat(result.getLeft().getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        verify(milestoneRepository, never()).delete(any());
        verify(fundingEventRepository, never()).delete(any());
    }

    @Test
    void deleteMilestone_flagsEvent_withoutTouchingItsAllocations() {
        MilestoneEntity milestone = milestone("m1");
        EventMilestoneAllocationEntity alloc = allocation("e1", "m1", "50000");
        FundingEventEntity event = fundingEvent("e1", EventType.FUNDING, alloc);
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(allocationRepository.findEventIdsByMilestoneIdIn(any())).thenReturn(List.of("e1"));
        when(fundingEventRepository.findAllById(any())).thenReturn(List.of(event));

        Either<ProblemDetail, List<FundingEventEntity>> result = service.deleteMilestone(milestone);

        assertThat(result.get()).containsExactly(event);
        assertThat(event.getStatus()).isEqualTo(EventStatus.ERROR);
        // the allocation row is left exactly as it was — no FK enforces milestone_id any more
        // (V1.8_200_9), so it's fine for it to now point at a milestone that's about to be deleted
        assertThat(event.getMilestoneAllocations()).containsExactly(alloc);
        verify(fundingEventRepository, never()).delete(any());       // event itself is never auto-deleted
        verify(fundingEventRepository).saveAll(List.of(event));
        verify(milestoneRepository).delete(milestone);
    }

    @Test
    void deleteMilestone_flagsEvent_whenAlsoAllocatedToOtherProjects() {
        MilestoneEntity milestone = milestone("m1");
        EventMilestoneAllocationEntity insideAlloc = allocation("e1", "m1", "60000");
        EventMilestoneAllocationEntity outsideAlloc = allocation("e1", "m2", "40000"); // m2 is outside the deleted scope
        FundingEventEntity event = fundingEvent("e1", EventType.FUNDING, insideAlloc, outsideAlloc);
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(allocationRepository.findEventIdsByMilestoneIdIn(any())).thenReturn(List.of("e1"));
        when(fundingEventRepository.findAllById(any())).thenReturn(List.of(event));

        Either<ProblemDetail, List<FundingEventEntity>> result = service.deleteMilestone(milestone);

        assertThat(result.get()).containsExactly(event);
        assertThat(event.getStatus()).isEqualTo(EventStatus.ERROR);
        // both allocations survive untouched, including the in-scope one now pointing at a deleted milestone
        assertThat(event.getMilestoneAllocations()).containsExactly(insideAlloc, outsideAlloc);
        verify(fundingEventRepository, never()).delete(any());
        verify(milestoneRepository).delete(milestone);
    }

    @Test
    void deleteMilestone_deletesMilestone_whenNoAllocations() {
        MilestoneEntity milestone = milestone("m1");
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(allocationRepository.findEventIdsByMilestoneIdIn(any())).thenReturn(List.of());

        Either<ProblemDetail, List<FundingEventEntity>> result = service.deleteMilestone(milestone);

        assertThat(result.get()).isEmpty();
        verify(milestoneRepository).delete(milestone);
        verify(fundingEventRepository, never()).delete(any());
        verify(fundingEventRepository, never()).saveAll(any());
    }

    // --- deleteProjectSubtree ---

    @Test
    void deleteProjectSubtree_blocks_whenPublishedAnywhereInSubtree() {
        ProjectEntity root = project("p1");
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of());
        when(milestoneRepository.findByProjectIdIn(any())).thenReturn(List.of(milestone("m1")));
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(true);

        Either<ProblemDetail, List<FundingEventEntity>> result = service.deleteProjectSubtree(root);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
        verify(projectRepository, never()).delete(any());
    }

    @Test
    void deleteProjectSubtree_deletesSubtree_whenClean() {
        ProjectEntity root = project("p1");
        ProjectEntity child = project("p2");
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of(child)); // walks into sub-project
        when(projectRepository.findByParentProjectId("p2")).thenReturn(List.of());
        when(milestoneRepository.findByProjectIdIn(any())).thenReturn(List.of(milestone("m1")));
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(allocationRepository.findEventIdsByMilestoneIdIn(any())).thenReturn(List.of());

        Either<ProblemDetail, List<FundingEventEntity>> result = service.deleteProjectSubtree(root);

        assertThat(result.get()).isEmpty();
        verify(projectRepository).delete(root); // JPA cascade removes sub-projects + milestones
    }

    @Test
    void deleteProjectSubtree_flagsEvent_whenAlsoAllocatedOutsideSubtree() {
        ProjectEntity root = project("p1");
        EventMilestoneAllocationEntity insideAlloc = allocation("e1", "m1", "60000");
        EventMilestoneAllocationEntity outsideAlloc = allocation("e1", "m-other", "40000"); // milestone of another project
        FundingEventEntity event = fundingEvent("e1", EventType.FUNDING, insideAlloc, outsideAlloc);
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of());
        when(milestoneRepository.findByProjectIdIn(any())).thenReturn(List.of(milestone("m1")));
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(allocationRepository.findEventIdsByMilestoneIdIn(any())).thenReturn(List.of("e1"));
        when(fundingEventRepository.findAllById(any())).thenReturn(List.of(event));

        Either<ProblemDetail, List<FundingEventEntity>> result = service.deleteProjectSubtree(root);

        assertThat(result.get()).containsExactly(event);
        assertThat(event.getStatus()).isEqualTo(EventStatus.ERROR);
        // both allocations survive untouched, including the in-scope one now pointing at a deleted milestone
        assertThat(event.getMilestoneAllocations()).containsExactly(insideAlloc, outsideAlloc);
        verify(fundingEventRepository, never()).delete(any());
        verify(projectRepository).delete(root);
    }

    // --- flagEventsAllocatedTo (shrink / currency change / delete share it) ---

    @Test
    void flagEventsAllocatedTo_flagsDraftEvent_withoutTouchingItsAllocations() {
        EventMilestoneAllocationEntity alloc = allocation("e1", "m1", "50000");
        FundingEventEntity event = fundingEvent("e1", EventType.FUNDING, alloc); // status DRAFT
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(allocationRepository.findEventIdsByMilestoneIdIn(Set.of("m1"))).thenReturn(List.of("e1"));
        when(fundingEventRepository.findAllById(List.of("e1"))).thenReturn(List.of(event));

        Either<ProblemDetail, List<FundingEventEntity>> result = service.flagEventsAllocatedTo(Set.of("m1"));

        assertThat(result.get()).containsExactly(event);
        assertThat(event.getStatus()).isEqualTo(EventStatus.ERROR);
        verify(fundingEventRepository).saveAll(List.of(event));
        // Never deleted, and never touches the allocation's own recorded amount — only the status changes.
        verify(fundingEventRepository, never()).delete(any());
        assertThat(alloc.getAllocatedAmount()).isEqualByComparingTo("50000");
    }

    @Test
    void flagEventsAllocatedTo_flagsEvent_evenWhenItAlsoAllocatesOutsideTheGivenMilestones() {
        EventMilestoneAllocationEntity insideAlloc = allocation("e1", "m1", "60000");
        EventMilestoneAllocationEntity outsideAlloc = allocation("e1", "m-other", "40000");
        FundingEventEntity event = fundingEvent("e1", EventType.FUNDING, insideAlloc, outsideAlloc);
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(allocationRepository.findEventIdsByMilestoneIdIn(Set.of("m1"))).thenReturn(List.of("e1"));
        when(fundingEventRepository.findAllById(List.of("e1"))).thenReturn(List.of(event));

        Either<ProblemDetail, List<FundingEventEntity>> result = service.flagEventsAllocatedTo(Set.of("m1"));

        assertThat(result.get()).containsExactly(event);
        assertThat(event.getStatus()).isEqualTo(EventStatus.ERROR);
        assertThat(event.getMilestoneAllocations()).containsExactly(insideAlloc, outsideAlloc);
    }

    @Test
    void flagEventsAllocatedTo_blocks_whenAnAllocatedEventIsPublished() {
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(true);

        Either<ProblemDetail, List<FundingEventEntity>> result = service.flagEventsAllocatedTo(Set.of("m1"));

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
        verify(fundingEventRepository, never()).saveAll(any());
    }

    @Test
    void flagEventsAllocatedTo_reportsAnAlreadyErroredEvent_andLeavesItInError() {
        EventMilestoneAllocationEntity alloc = allocation("e1", "m1", "50000");
        FundingEventEntity event = fundingEvent("e1", EventType.FUNDING, alloc);
        event.setStatus(EventStatus.ERROR);
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(allocationRepository.findEventIdsByMilestoneIdIn(Set.of("m1"))).thenReturn(List.of("e1"));
        when(fundingEventRepository.findAllById(List.of("e1"))).thenReturn(List.of(event));

        Either<ProblemDetail, List<FundingEventEntity>> result = service.flagEventsAllocatedTo(Set.of("m1"));

        assertThat(result.get()).containsExactly(event);
        assertThat(event.getStatus()).isEqualTo(EventStatus.ERROR);
    }

    @Test
    void flagEventsAllocatedTo_returnsNothing_whenNoEventIsAllocated() {
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(allocationRepository.findEventIdsByMilestoneIdIn(Set.of("m1"))).thenReturn(List.of());

        Either<ProblemDetail, List<FundingEventEntity>> result = service.flagEventsAllocatedTo(Set.of("m1"));

        assertThat(result.get()).isEmpty();
        verify(fundingEventRepository, never()).saveAll(any());
    }

    // --- helpers ---

    private ProjectEntity project(String id) {
        return ProjectEntity.builder().id(id).organisationId("org1").externalProjectId("EXT-" + id).projectTitle("Project " + id).build();
    }

    private MilestoneEntity milestone(String id) {
        return MilestoneEntity.builder().id(id).milestoneTitle("Milestone " + id)
                .milestoneAmount(new BigDecimal("50000")).currency("USD").milestoneDate(LocalDate.of(2027, 1, 1)).build();
    }

    private EventMilestoneAllocationEntity allocation(String eventId, String milestoneId, String amount) {
        return EventMilestoneAllocationEntity.builder()
                .id(new EventMilestoneAllocationEntity.Id(eventId, milestoneId))
                .allocatedAmount(amount == null ? null : new BigDecimal(amount))
                .build();
    }

    private FundingEventEntity fundingEvent(String id, EventType type, EventMilestoneAllocationEntity... allocations) {
        return FundingEventEntity.builder()
                .id(id).eventType(type).status(EventStatus.DRAFT).organisationId("org1").fundingId("GRANT-1").currencyRcy("USD")
                .totalAmount(BigDecimal.ZERO)
                .milestoneAllocations(new ArrayList<>(List.of(allocations)))
                .build();
    }

    @Test
    void deleteProjectSubtree_deletesWithoutQueryingEvents_whenSubtreeHasNoMilestones() {
        ProjectEntity root = project("p1");
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of());
        when(milestoneRepository.findByProjectIdIn(any())).thenReturn(List.of());

        Either<ProblemDetail, List<FundingEventEntity>> result = service.deleteProjectSubtree(root);

        assertThat(result.get()).isEmpty();
        verify(projectRepository).delete(root);
        verifyNoInteractions(allocationRepository);
    }

    @Test
    void flagEventsAllocatedTo_noOp_whenGivenNoMilestones() {
        Either<ProblemDetail, List<FundingEventEntity>> result = service.flagEventsAllocatedTo(Set.of());

        assertThat(result.get()).isEmpty();
        verifyNoInteractions(allocationRepository);
    }

}
