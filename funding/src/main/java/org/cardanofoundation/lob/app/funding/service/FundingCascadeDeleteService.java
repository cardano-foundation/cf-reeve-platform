package org.cardanofoundation.lob.app.funding.service;

import java.util.LinkedHashSet;
import java.util.List;
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
import org.cardanofoundation.lob.app.funding.domain.view.AffectedEventView;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingEventRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.funding.util.Problems;

/**
 * Cross-aggregate operations spanning projects/milestones and the events allocated to them.
 * Deleting a project or milestone must fail when anything in its scope is tied to a PUBLISHED event;
 * otherwise the object and everything it owns is removed. Every non-published event allocated to a
 * milestone in the deleted scope is flagged {@code ERROR} — its own data (allocations included) is
 * never touched, only its status (LOB-2365 follow-up: {@code funding_event_milestone_allocation.
 * milestone_id} is no longer FK-enforced — see migration {@code V1.8_200_9} and
 * {@code EventMilestoneAllocationEntity#milestone} — so an allocation row can simply outlive the
 * milestone it names, standing as a dangling reference for a human to review, rather than having to be
 * removed to let the milestone be deleted at all). Deleting the event itself, if ever warranted, is left
 * as a separate, deliberate action a human takes afterward via the normal event-delete endpoint (which
 * already tolerates {@code ERROR} — only {@code PUBLISHED} blocks it).
 *
 * <p>The same rule ({@link #flagEventsAllocatedTo}) also serves every other structural change that can
 * leave an event out of step with its milestones: shrinking a milestone below what is allocated to it,
 * and changing a milestone's (or its project's) currency, since an event books in one currency that must
 * equal each milestone's. In all of them an event's own recorded data is never rewritten, only its
 * status, and it does not matter whether the event also allocates to milestones elsewhere — the event no
 * longer fits either way, so a human reconciles it.
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
     * nothing) when any milestone in the subtree is linked to a published event; otherwise every
     * non-published event allocated to a milestone in the subtree is flagged {@code ERROR} (see class
     * Javadoc), and the project/milestone rows are removed via JPA cascade — their allocation rows are
     * left exactly as they were, now pointing at nothing. On success, carries the (possibly empty) list
     * of events flagged, so the caller can report them to the human who triggered the delete (see
     * {@link #toAffectedEventViews}).
     */
    @Transactional
    public Either<ProblemDetail, List<FundingEventEntity>> deleteProjectSubtree(ProjectEntity project) {
        Set<String> subtreeProjectIds = ProjectTreeSupport.subtreeProjectIds(projectRepository, project.getId());
        Set<String> milestoneIds = milestoneRepository.findByProjectIdIn(subtreeProjectIds).stream()
                .map(MilestoneEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Either<ProblemDetail, List<FundingEventEntity>> result = flagEventsAllocatedTo(milestoneIds);
        if (result.isLeft()) {
            return result;
        }
        // ProjectEntity cascades ALL to sub-projects and milestones, so removing the root removes the
        // whole subtree; no FK on funding_event_milestone_allocation.milestone_id blocks this any more
        // (V1.8_200_9) — any allocation row referencing a milestone in this subtree simply survives it.
        projectRepository.delete(project);
        return result;
    }

    /**
     * Deletes a single milestone. Fails when it is linked to a published event; otherwise every
     * non-published event allocated to it is flagged {@code ERROR} (see class Javadoc), and the
     * milestone itself is removed — its allocation rows are left exactly as they were, now pointing at
     * nothing. On success, carries the (possibly empty) list of events flagged (see
     * {@link #toAffectedEventViews}).
     */
    @Transactional
    public Either<ProblemDetail, List<FundingEventEntity>> deleteMilestone(MilestoneEntity milestone) {
        Either<ProblemDetail, List<FundingEventEntity>> result = flagEventsAllocatedTo(Set.of(milestone.getId()));
        if (result.isLeft()) {
            return result;
        }
        milestoneRepository.delete(milestone);
        return result;
    }

    /** Maps the events a delete/cleanup operation touched to the shared response DTO. */
    public static List<AffectedEventView> toAffectedEventViews(List<FundingEventEntity> events) {
        return events.stream()
                .map(event -> AffectedEventView.builder().eventId(event.getId()).fundingId(event.getFundingId()).build())
                .toList();
    }

    /**
     * Fails — leaving all data untouched — when any of the given milestones is linked to a published
     * event; otherwise flags every non-published event allocated to one of them {@code ERROR} (see
     * {@link #flagEvents}) and returns the (possibly empty) list of events touched. Callers use it for
     * a milestone about to be deleted, shrunk below its allocations, or changed to another currency.
     */
    @Transactional
    public Either<ProblemDetail, List<FundingEventEntity>> flagEventsAllocatedTo(Set<String> milestoneIds) {
        if (milestoneIds.isEmpty()) {
            return Either.right(List.of());
        }
        if (allocationRepository.existsByMilestoneIdInAndEventStatus(milestoneIds, EventStatus.PUBLISHED)) {
            return Either.left(Problems.conflict(
                    "Cannot proceed: a linked event is already published",
                    ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED));
        }
        return Either.right(flagEvents(milestoneIds));
    }

    /**
     * Flags {@code ERROR} every event allocated to at least one milestone in {@code milestoneIds} —
     * regardless of whether it also allocates elsewhere outside the set — and returns the events
     * touched. Nothing about any event's own data, including its allocation rows, is ever rewritten here
     * — only the status changes; the allocation rows themselves are left for the caller's own delete
     * (of the milestone/project) to leave dangling, not removed by this method (see class Javadoc).
     */
    private List<FundingEventEntity> flagEvents(Set<String> milestoneIds) {
        // Ids only, deliberately — see EventMilestoneAllocationRepository#findEventIdsByMilestoneIdIn.
        List<String> eventIds = allocationRepository.findEventIdsByMilestoneIdIn(milestoneIds);
        if (eventIds.isEmpty()) {
            return List.of();
        }
        List<FundingEventEntity> events = fundingEventRepository.findAllById(eventIds);
        events.forEach(event -> event.setStatus(EventStatus.ERROR));
        fundingEventRepository.saveAll(events);
        fundingEventRepository.flush();
        return events;
    }

}
