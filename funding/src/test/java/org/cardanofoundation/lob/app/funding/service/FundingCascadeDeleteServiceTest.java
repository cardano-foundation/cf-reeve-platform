package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

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

        Optional<ProblemDetail> result = service.deleteMilestone(milestone("m1"));

        assertThat(result.orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
        assertThat(result.orElseThrow().getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        verify(milestoneRepository, never()).delete(any());
        verify(fundingEventRepository, never()).delete(any());
    }

    @Test
    void deleteMilestone_deletesWholeEvent_whenFullyInside() {
        MilestoneEntity milestone = milestone("m1");
        EventMilestoneAllocationEntity alloc = allocation("e1", "m1", "50000");
        FundingEventEntity event = fundingEvent("e1", EventType.FUNDING, alloc);
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(allocationRepository.findById_MilestoneIdIn(any())).thenReturn(List.of(alloc));
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(event));

        Optional<ProblemDetail> result = service.deleteMilestone(milestone);

        assertThat(result).isEmpty();
        verify(fundingEventRepository).delete(event);       // event had only this milestone -> deleted whole
        verify(fundingEventRepository, never()).save(any());
        verify(milestoneRepository).delete(milestone);
    }

    @Test
    void deleteMilestone_fails_whenEventAllocatedToOtherProjects() {
        MilestoneEntity milestone = milestone("m1");
        EventMilestoneAllocationEntity insideAlloc = allocation("e1", "m1", "60000");
        EventMilestoneAllocationEntity outsideAlloc = allocation("e1", "m2", "40000"); // m2 is outside the deleted scope
        FundingEventEntity event = fundingEvent("e1", EventType.FUNDING, insideAlloc, outsideAlloc);
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(allocationRepository.findById_MilestoneIdIn(any())).thenReturn(List.of(insideAlloc));
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(event));

        Optional<ProblemDetail> result = service.deleteMilestone(milestone);

        assertThat(result.orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.EVENT_ALLOCATED_TO_OTHER_PROJECTS);
        // no changes: nothing deleted or modified
        verify(fundingEventRepository, never()).delete(any());
        verify(fundingEventRepository, never()).save(any());
        verify(milestoneRepository, never()).delete(any());
        assertThat(event.getMilestoneAllocations()).hasSize(2);
    }

    @Test
    void deleteMilestone_deletesMilestone_whenNoAllocations() {
        MilestoneEntity milestone = milestone("m1");
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(allocationRepository.findById_MilestoneIdIn(any())).thenReturn(List.of());

        Optional<ProblemDetail> result = service.deleteMilestone(milestone);

        assertThat(result).isEmpty();
        verify(milestoneRepository).delete(milestone);
        verify(fundingEventRepository, never()).delete(any());
    }

    // --- deleteProjectSubtree ---

    @Test
    void deleteProjectSubtree_blocks_whenPublishedAnywhereInSubtree() {
        ProjectEntity root = project("p1");
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of());
        when(milestoneRepository.findByProjectIdIn(any())).thenReturn(List.of(milestone("m1")));
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(true);

        Optional<ProblemDetail> result = service.deleteProjectSubtree(root);

        assertThat(result.orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
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
        when(allocationRepository.findById_MilestoneIdIn(any())).thenReturn(List.of());

        Optional<ProblemDetail> result = service.deleteProjectSubtree(root);

        assertThat(result).isEmpty();
        verify(projectRepository).delete(root); // JPA cascade removes sub-projects + milestones
    }

    @Test
    void deleteProjectSubtree_fails_whenEventAllocatedOutsideSubtree() {
        ProjectEntity root = project("p1");
        EventMilestoneAllocationEntity insideAlloc = allocation("e1", "m1", "60000");
        EventMilestoneAllocationEntity outsideAlloc = allocation("e1", "m-other", "40000"); // milestone of another project
        FundingEventEntity event = fundingEvent("e1", EventType.FUNDING, insideAlloc, outsideAlloc);
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of());
        when(milestoneRepository.findByProjectIdIn(any())).thenReturn(List.of(milestone("m1")));
        when(allocationRepository.existsByMilestoneIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(allocationRepository.findById_MilestoneIdIn(any())).thenReturn(List.of(insideAlloc));
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(event));

        Optional<ProblemDetail> result = service.deleteProjectSubtree(root);

        assertThat(result.orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.EVENT_ALLOCATED_TO_OTHER_PROJECTS);
        verify(projectRepository, never()).delete(any());
        verify(fundingEventRepository, never()).delete(any());
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
                .id(id).eventType(type).status(EventStatus.DRAFT).organisationId("org1").fundingId("GRANT-1").currency("USD")
                .totalAmount(BigDecimal.ZERO)
                .milestoneAllocations(new ArrayList<>(List.of(allocations)))
                .build();
    }
}
