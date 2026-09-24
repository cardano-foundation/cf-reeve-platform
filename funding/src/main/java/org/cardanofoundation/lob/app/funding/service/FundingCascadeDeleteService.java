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
 * milestone in the deleted scope has that allocation detached and is flagged {@code ERROR} — never
 * deleted itself, whether or not it also allocates elsewhere outside the deleted scope. Deleting the
 * event, if ever warranted, is left as a separate, deliberate action a human takes afterward via the
 * normal event-delete endpoint (which already tolerates {@code ERROR} — only {@code PUBLISHED} blocks
 * it). This mirrors {@link #markContainedEventsAsErrorOrBlock}, LOB-2365's milestone-amount-shrink
 * flow, which flags rather than blocks for the same "real recorded money can't be un-recorded" reason —
 * the two differ only in that a shrink never removes an allocation row, while a delete must (the
 * milestone it points to no longer exists).
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
     * non-published event allocated to a milestone in the subtree is detached and flagged {@code ERROR}
     * (see class Javadoc), and the project/milestone rows are removed via JPA cascade. On success,
     * carries the (possibly empty) list of events that were detached and flagged, so the caller can
     * report them to the human who triggered the delete (see {@link #toAffectedEventViews}).
     */
    @Transactional
    public Either<ProblemDetail, List<FundingEventEntity>> deleteProjectSubtree(ProjectEntity project) {
        Set<String> subtreeProjectIds = ProjectTreeSupport.subtreeProjectIds(projectRepository, project.getId());
        Set<String> milestoneIds = milestoneRepository.findByProjectIdIn(subtreeProjectIds).stream()
                .map(MilestoneEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Either<ProblemDetail, List<FundingEventEntity>> result = detachEventsOrBlock(milestoneIds);
        if (result.isLeft()) {
            return result;
        }
        // ProjectEntity cascades ALL to sub-projects and milestones, so removing the root removes the
        // whole subtree; the allocations that referenced those milestones are already detached.
        projectRepository.delete(project);
        return result;
    }

    /**
     * Deletes a single milestone. Fails when it is linked to a published event; otherwise every
     * non-published event allocated to it is detached and flagged {@code ERROR} (see class Javadoc),
     * and the milestone itself is removed. On success, carries the (possibly empty) list of events that
     * were detached and flagged (see {@link #toAffectedEventViews}).
     */
    @Transactional
    public Either<ProblemDetail, List<FundingEventEntity>> deleteMilestone(MilestoneEntity milestone) {
        Either<ProblemDetail, List<FundingEventEntity>> result = detachEventsOrBlock(Set.of(milestone.getId()));
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
     * {@link #resolveEventsFullyContained} — unlike a delete (see {@link #detachAndFlagEvents}), a shrink
     * never removes an allocation row, so an event only partly affected by the shrink is left entirely
     * alone rather than partially flagged. No published event can be fully contained here in practice:
     * the caller's own lock check already rejects the edit outright once any published event exists in
     * scope, before this method is ever reached — this mechanism is exclusively a milestone
     * *structural-update* concern, never something the event create/update/delete endpoints themselves
     * trigger or are affected by.
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
     * event; otherwise detaches every non-published event's allocation(s) into this scope, flags it
     * {@code ERROR} (see {@link #detachAndFlagEvents}), and returns the (possibly empty) list of events
     * touched.
     */
    private Either<ProblemDetail, List<FundingEventEntity>> detachEventsOrBlock(Set<String> milestoneIds) {
        if (milestoneIds.isEmpty()) {
            return Either.right(List.of());
        }
        if (allocationRepository.existsByMilestoneIdInAndEventStatus(milestoneIds, EventStatus.PUBLISHED)) {
            return Either.left(Problems.conflict(
                    "Cannot delete: a linked event is already published",
                    ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED));
        }
        return Either.right(detachAndFlagEvents(milestoneIds));
    }

    /**
     * Removes, from every event allocated to at least one milestone in {@code milestoneIds}, the
     * allocation row(s) pointing into that set, flags the event {@code ERROR} — regardless of whether
     * the event also allocates elsewhere outside the set (a milestone about to be deleted can never
     * "cover" a positive allocation, the same reasoning {@code MilestoneService#needsErrorFlagging}
     * already applies to a shrink) — and returns the events touched. Allocations are removed via
     * {@code FundingEventEntity}'s own managed {@code milestoneAllocations} collection (mapped with
     * {@code orphanRemoval = true}), not a direct repository delete, so Hibernate — not this method —
     * decides the DML ordering against the milestone/project rows this method's caller deletes
     * immediately afterward.
     */
    private List<FundingEventEntity> detachAndFlagEvents(Set<String> milestoneIds) {
        List<String> eventIds = allocationRepository.findById_MilestoneIdIn(milestoneIds).stream()
                .map(allocation -> allocation.getId().getEventId())
                .distinct()
                .toList();
        if (eventIds.isEmpty()) {
            return List.of();
        }
        List<FundingEventEntity> events = fundingEventRepository.findAllById(eventIds);
        for (FundingEventEntity event : events) {
            event.getMilestoneAllocations().removeIf(allocation -> milestoneIds.contains(allocation.getId().getMilestoneId()));
            event.setStatus(EventStatus.ERROR);
        }
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
