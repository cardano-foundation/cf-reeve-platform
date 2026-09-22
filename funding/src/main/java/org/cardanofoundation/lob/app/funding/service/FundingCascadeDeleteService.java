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

import io.vavr.control.Either;

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
 * Cross-aggregate operations spanning projects/milestones and the events allocated to them.
 * Deleting a project or milestone must fail when anything in its scope is tied to a PUBLISHED event,
 * or to an event that also allocates to projects outside the deleted scope; otherwise the object and
 * everything it owns is removed, along with the draft events that lived entirely within that scope.
 * The same "resolve every event fully contained in a scope, or block if one reaches outside it" logic
 * is also reused by {@link #markContainedEventsAsErrorOrBlock} for LOB-2365's project-total-shrink
 * flow, which needs the same cross-project safety net but a different outcome (flag, not delete).
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
     * Marks every draft event fully contained in {@code projectId}'s subtree as {@link EventStatus#ERROR}
     * — used when a project's total amount is shrunk below what its children currently claim (see
     * {@code FundingValidations#projectTotalCoversChildren}), which {@code ProjectService#updateProject}
     * now allows through rather than rejecting outright (LOB-2365). See
     * {@link #markContainedEventsAsErrorOrBlock(Set)} for the shared mechanics, also used by
     * {@code MilestoneService#update} for the exact same relaxation one level down.
     */
    @Transactional
    public Optional<ProblemDetail> markContainedEventsAsErrorOrBlock(String projectId) {
        Set<String> subtreeProjectIds = ProjectTreeSupport.subtreeProjectIds(projectRepository, projectId);
        Set<String> milestoneIds = milestoneRepository.findByProjectIdIn(subtreeProjectIds).stream()
                .map(MilestoneEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // Calls the shared helper directly rather than the Set<String> overload below via `this` — a
        // same-class call to another @Transactional method bypasses Spring's AOP proxy entirely.
        return doMarkContainedEventsAsErrorOrBlock(milestoneIds);
    }

    /**
     * Marks every draft event fully contained in {@code milestoneIds} as {@link EventStatus#ERROR} —
     * nothing about any milestone's own recorded amount, or any event's own allocated figures, is ever
     * rewritten; only the event's status changes, flagging that a human needs to review and fix it
     * before it can ever be published. Used by {@code ProjectService#updateProject} (a whole subtree's
     * worth of milestone ids) and by {@code MilestoneService#update} (a single milestone's id, when its
     * own amount is shrunk below what's already allocated to it — see
     * {@code FundingValidations#milestoneCoversAllocations}, which that method now allows through
     * rather than rejecting outright, the same relaxation as the project-level case, one level down).
     *
     * <p>An event that also allocates to a milestone outside this set (i.e. it also represents money
     * somewhere untouched by the current edit) is still a hard block instead — same cross-project
     * safety net {@link #deleteAssociatedEventsOrBlock} already uses, reused here via
     * {@link #resolveEventsFullyContained}. No published event can be fully contained here in
     * practice: both callers' own lock checks already reject their respective edit outright once any
     * published event exists in scope, before this method is ever reached — this mechanism is
     * exclusively a project/milestone *structural-update* concern, never something the event
     * create/update/delete endpoints themselves trigger or are affected by.
     */
    @Transactional
    public Optional<ProblemDetail> markContainedEventsAsErrorOrBlock(Set<String> milestoneIds) {
        return doMarkContainedEventsAsErrorOrBlock(milestoneIds);
    }

    private Optional<ProblemDetail> doMarkContainedEventsAsErrorOrBlock(Set<String> milestoneIds) {
        Either<ProblemDetail, List<FundingEventEntity>> eventsOrBlocked = resolveEventsFullyContained(milestoneIds, "update");
        if (eventsOrBlocked.isLeft()) {
            return Optional.of(eventsOrBlocked.getLeft());
        }
        List<FundingEventEntity> toFlag = eventsOrBlocked.get().stream()
                .filter(event -> event.getStatus() != EventStatus.ERROR) // idempotent — already-flagged events are left as is
                .toList();
        toFlag.forEach(event -> event.setStatus(EventStatus.ERROR));
        fundingEventRepository.saveAll(toFlag);
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

        Either<ProblemDetail, List<FundingEventEntity>> eventsOrBlocked = resolveEventsFullyContained(milestoneIds, "delete");
        if (eventsOrBlocked.isLeft()) {
            return Optional.of(eventsOrBlocked.getLeft());
        }
        eventsOrBlocked.get().forEach(fundingEventRepository::delete);
        fundingEventRepository.flush();
        return Optional.empty();
    }

    /**
     * Every distinct event associated with at least one milestone in {@code milestoneIds}. Left with
     * {@code EVENT_ALLOCATED_TO_OTHER_PROJECTS} when any such event also allocates to a milestone
     * outside that set — i.e. it reaches into a different, untouched part of the tree, so the caller's
     * operation (naming itself via {@code actionVerb}, e.g. "delete"/"update") must not proceed.
     */
    private Either<ProblemDetail, List<FundingEventEntity>> resolveEventsFullyContained(Set<String> milestoneIds, String actionVerb) {
        if (milestoneIds.isEmpty()) {
            return Either.right(List.of());
        }
        Set<String> eventIds = allocationRepository.findById_MilestoneIdIn(milestoneIds).stream()
                .map(allocation -> allocation.getId().getEventId())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<FundingEventEntity> events = new ArrayList<>();
        for (String eventId : eventIds) {
            FundingEventEntity event = fundingEventRepository.findById(eventId).orElseThrow();
            boolean fullyInside = event.getMilestoneAllocations().stream()
                    .allMatch(allocation -> milestoneIds.contains(allocation.getId().getMilestoneId()));
            if (!fullyInside) {
                return Either.left(Problems.conflict(
                        "Cannot %s: an associated event also allocates to other projects".formatted(actionVerb),
                        ErrorTitleConstants.EVENT_ALLOCATED_TO_OTHER_PROJECTS));
            }
            events.add(event);
        }
        return Either.right(events);
    }
}
