package org.cardanofoundation.lob.app.funding.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingEventRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.funding.util.Problems;

/**
 * Cross-aggregate cascade deletion for the funding domain. Deleting a project or milestone must fail
 * when anything in its scope is tied to a PUBLISHED event, or to an event that also allocates to
 * projects outside the deleted scope; otherwise the object and everything it owns is removed, along
 * with the draft events that lived entirely within that scope.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundingCascadeDeleteService {

    private final FundingProjectRepository projectRepository;
    private final MilestoneRepository milestoneRepository;
    private final EventMilestoneAllocationRepository allocationRepository;
    private final FundingEventRepository fundingEventRepository;

    /**
     * Deletes a project with its descendant sub-projects and all their milestones. Fails (deleting
     * nothing) when any milestone in the subtree is linked to a published event, or to an event that
     * also allocates outside the subtree. Project and milestone rows are removed via JPA cascade once
     * the fully-contained draft events are deleted.
     */
    @Transactional
    public Optional<ProblemDetail> deleteProjectSubtree(ProjectEntity project) {
        Set<String> subtreeProjectIds = ProjectTreeSupport.subtreeProjectIds(projectRepository, project.getId());
        Set<String> milestoneIds = milestoneRepository.findByProjectIdIn(subtreeProjectIds).stream()
                .map(MilestoneEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Optional<ProblemDetail> blocked = deleteAssociatedEventsOrBlock(milestoneIds);
        if (blocked.isPresent()) {
            return blocked;
        }
        // ProjectEntity cascades ALL to sub-projects and milestones, so removing the root removes the
        // whole subtree; the allocations that referenced those milestones are already gone.
        projectRepository.delete(project);
        return Optional.empty();
    }

    /**
     * Deletes a single milestone. Fails when it is linked to a published event, or to an event that
     * also allocates to other milestones; otherwise removes the events fully contained by it and the
     * milestone itself.
     */
    @Transactional
    public Optional<ProblemDetail> deleteMilestone(MilestoneEntity milestone) {
        Optional<ProblemDetail> blocked = deleteAssociatedEventsOrBlock(Set.of(milestone.getId()));
        if (blocked.isPresent()) {
            return blocked;
        }
        milestoneRepository.delete(milestone);
        return Optional.empty();
    }

    /**
     * Validates then removes the events associated with the given milestones. Fails — leaving all data
     * untouched — when any is linked to a published event, or to an event that also allocates outside
     * the deleted scope (i.e. to milestones not in the set). Only when every associated event is fully
     * contained are those events deleted (cascading their items and allocations).
     */
    private Optional<ProblemDetail> deleteAssociatedEventsOrBlock(Set<String> milestoneIds) {
        if (milestoneIds.isEmpty()) {
            return Optional.empty();
        }
        if (allocationRepository.existsByMilestoneIdInAndEventStatus(milestoneIds, EventStatus.PUBLISHED)) {
            return Optional.of(Problems.conflict(
                    "Cannot delete: a linked event is already published",
                    ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED));
        }

        Set<String> eventIds = allocationRepository.findById_MilestoneIdIn(milestoneIds).stream()
                .map(allocation -> allocation.getId().getEventId())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Validate every associated event up front so a failure makes no changes at all.
        List<FundingEventEntity> events = new ArrayList<>();
        for (String eventId : eventIds) {
            FundingEventEntity event = fundingEventRepository.findById(eventId).orElseThrow();
            boolean fullyInside = event.getMilestoneAllocations().stream()
                    .allMatch(allocation -> milestoneIds.contains(allocation.getId().getMilestoneId()));
            if (!fullyInside) {
                return Optional.of(Problems.conflict(
                        "Cannot delete: an associated event also allocates to other projects",
                        ErrorTitleConstants.EVENT_ALLOCATED_TO_OTHER_PROJECTS));
            }
            events.add(event);
        }
        events.forEach(fundingEventRepository::delete);
        fundingEventRepository.flush();
        return Optional.empty();
    }
}
