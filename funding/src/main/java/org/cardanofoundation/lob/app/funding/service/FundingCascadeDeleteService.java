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
 * already tolerates {@code ERROR} — only {@code PUBLISHED} blocks it). This mirrors
 * {@link #markContainedEventsAsErrorOrBlock}, LOB-2365's milestone-amount-shrink flow, which flags for
 * the same "real recorded money can't be un-recorded" reason — the two now share the exact same
 * invariant: an event's own recorded data is never rewritten by a structural change elsewhere, only its
 * status.
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

        Either<ProblemDetail, List<FundingEventEntity>> result = flagEventsOrBlock(milestoneIds);
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
        Either<ProblemDetail, List<FundingEventEntity>> result = flagEventsOrBlock(Set.of(milestone.getId()));
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
     * Marks every draft event fully contained in {@code milestoneIds} as {@link EventStatus#ERROR} —
     * nothing about any milestone's own recorded amount, or any event's own allocated figures, is ever
     * rewritten; only the event's status changes, flagging that a human needs to review and fix it
     * before it can ever be published. Used by {@code MilestoneService#update} when a milestone's own
     * amount is shrunk below what's already allocated to it (see
     * {@code FundingValidations#milestoneCoversAllocations}, which that method allows through rather
     * than rejecting outright — real recorded money can't be un-recorded, so a human has to reconcile
     * it instead). A project's own total vs. its children's *declared* budgets is a different,
     * stricter case — see {@code FundingValidations#projectTotalCoversChildren}, a hard reject at
     * {@code ProjectTreeUpdateService#updateWithMilestones}, not a flag (LOB-2365 follow-up).
     *
     * <p>An event that also allocates to a milestone outside this set (i.e. it also represents money
     * somewhere untouched by the current edit) is still a hard block instead, via
     * {@link #resolveEventsFullyContained} — a shrink, unlike a delete (see {@link #flagEvents}), leaves
     * the milestone itself in place, so there's no equivalent reason to flag an event only partly
     * affected by it; that event is left entirely alone rather than partially flagged. No published
     * event can be fully contained here in practice: the caller's own lock check already rejects the
     * edit outright once any published event exists in scope, before this method is ever reached — this
     * mechanism is exclusively a milestone *structural-update* concern, never something the event
     * create/update/delete endpoints themselves trigger or are affected by.
     */
    @Transactional
    public Optional<ProblemDetail> markContainedEventsAsErrorOrBlock(Set<String> milestoneIds) {
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
     * Fails — leaving all data untouched — when any of the given milestones is linked to a published
     * event; otherwise flags every non-published event allocated to one of them {@code ERROR} (see
     * {@link #flagEvents}) and returns the (possibly empty) list of events touched.
     */
    private Either<ProblemDetail, List<FundingEventEntity>> flagEventsOrBlock(Set<String> milestoneIds) {
        if (milestoneIds.isEmpty()) {
            return Either.right(List.of());
        }
        if (allocationRepository.existsByMilestoneIdInAndEventStatus(milestoneIds, EventStatus.PUBLISHED)) {
            return Either.left(Problems.conflict(
                    "Cannot delete: a linked event is already published",
                    ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED));
        }
        return Either.right(flagEvents(milestoneIds));
    }

    /**
     * Flags {@code ERROR} every event allocated to at least one milestone in {@code milestoneIds} —
     * regardless of whether it also allocates elsewhere outside the set (a milestone about to be deleted
     * can never "cover" a positive allocation, the same reasoning
     * {@code MilestoneService#needsErrorFlagging} already applies to a shrink) — and returns the events
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
