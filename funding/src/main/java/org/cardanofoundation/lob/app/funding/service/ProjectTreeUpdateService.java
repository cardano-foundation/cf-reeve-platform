package org.cardanofoundation.lob.app.funding.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectTreeNodeRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.AffectedEventView;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.funding.util.FundingValidations;
import org.cardanofoundation.lob.app.funding.util.Problems;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Updates an existing root project's whole structure (itself, plus every sub-project and milestone
 * under it) in one atomic call — the update-side counterpart to
 * {@link ProjectService#createWithMilestones}, and, since LOB-2365's follow-up API-consistency pass,
 * the <em>only</em> project-update endpoint: {@code PUT /projects/{projectId}} identifies its target by
 * the internal id in the URL path (same meaning {@code GET}/{@code DELETE /projects/{projectId}}
 * already use), matching how {@code PUT /events/{eventId}} identifies an event — never a body field.
 * {@code projectId} must name a root project (no parent); the request's own {@code proId} field, if
 * present, is accepted but ignored for matching (a project's proId is immutable and was never editable
 * through this endpoint anyway). Nested nodes are still matched by {@code proId}, falling back to title
 * (see {@link ProjectTreeNodeRequest#proId} / {@link MilestoneCreateRequest#proId}), so a single PUT can
 * create, resize, or delete ({@link ProjectTreeNodeRequest#action}/{@link MilestoneCreateRequest#action})
 * the project and its children together.
 *
 * <p>This exists because validating budget-fit against whatever is <em>currently persisted</em> for a
 * node's siblings is correct for editing one node in isolation, but wrong for resizing several related
 * levels in the same request: shrinking a parent before its children hits the new hard block (see
 * {@code FundingValidations#projectTotalCoversChildren}), and shrinking a child before its parent hits
 * the existing one ({@code FundingValidations#subProjectAmount}/{@code #milestone}). This service instead
 * applies every field value across the whole touched tree first (top-down, so a parent's new value is
 * already in memory before its children are processed), then runs one consolidated structural pass over
 * the whole touched tree afterward using only final values — never a mid-walk, stale-sibling comparison.
 *
 * <p>Re-parenting a project (moving it to a different parent tree entirely) is deliberately not
 * supported by this endpoint — it used to be a narrow-endpoint-only capability, dropped in the LOB-2365
 * merge as an intentionally unsupported scenario (delete and recreate under the new parent instead).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectTreeUpdateService {

    private final FundingProjectRepository projectRepository;
    private final MilestoneRepository milestoneRepository;
    private final MilestoneService milestoneService;
    private final ProjectService projectService;
    private final ProjectStructureService projectStructureService;
    private final EventMilestoneAllocationRepository allocationRepository;
    private final FundingCascadeDeleteService cascadeDeleteService;
    private final KeycloakSecurityHelper keycloakSecurityHelper;

    @Transactional
    public ProjectView updateWithMilestones(String projectId, ProjectWithMilestonesCreateRequest request) {
        Optional<ProjectEntity> rootM = projectRepository.findById(projectId);
        if (rootM.isEmpty()) {
            return ProjectView.error(Problems.projectNotFound(projectId));
        }
        ProjectEntity root = rootM.get();
        if (!keycloakSecurityHelper.canUserAccessOrg(root.getOrganisationId())) {
            return ProjectView.error(Problems.unauthorized());
        }
        if (root.getParentProject() != null) {
            return ProjectView.error(Problems.badRequest(
                    "PUT /projects/{projectId} only updates a root project's whole structure; %s is a sub-project"
                            .formatted(projectId),
                    ErrorTitleConstants.PROJECT_NOT_ROOT));
        }

        // Whole-subtree publish lock, unconditional: if any PUBLISHED event exists anywhere in this
        // project's own subtree, no field of it or anything under it can be updated at all — same rule
        // as the narrow endpoints, applied up front here for the entire tree in one go.
        if (isLockedByPublishedEvent(root)) {
            return ProjectView.error(Problems.conflict(
                    "Cannot update: a published event exists in project %s's structure".formatted(root.getId()),
                    ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED));
        }

        Optional<ProblemDetail> xor = FundingValidations.milestonesXorSubProjects(
                !request.getMilestones().isEmpty(), !request.getSubProjects().isEmpty());
        if (xor.isPresent()) {
            return ProjectView.error(xor.get());
        }

        Set<String> changedMilestoneIds = new LinkedHashSet<>();
        Optional<ProblemDetail> rootFieldsProblem = applyRootFields(root, request, changedMilestoneIds);
        if (rootFieldsProblem.isPresent()) {
            return ProjectView.error(rootFieldsProblem.get());
        }

        Set<String> touchedProjectIds = new LinkedHashSet<>();
        List<AffectedEventView> affectedEvents = new ArrayList<>();
        Optional<ProblemDetail> childrenProblem = applyChildren(
                root, request.getMilestones(), request.getSubProjects(), touchedProjectIds, changedMilestoneIds, affectedEvents);
        if (childrenProblem.isPresent()) {
            rollbackOnly();
            return ProjectView.error(childrenProblem.get());
        }

        Optional<ProblemDetail> coverage = validateWholeTreeCoverage(touchedProjectIds);
        if (coverage.isPresent()) {
            rollbackOnly();
            return ProjectView.error(coverage.get());
        }

        Either<ProblemDetail, List<FundingEventEntity>> flagged = flagEventsOfChangedMilestones(changedMilestoneIds);
        if (flagged.isLeft()) {
            rollbackOnly();
            return ProjectView.error(flagged.getLeft());
        }
        addNewlyAffected(affectedEvents, FundingCascadeDeleteService.toAffectedEventViews(flagged.get()));

        // includeEvents=true: the exact same view-building call GET /projects/{projectId} makes, so a
        // PUT's response is never a thinner shape than GET's — one method controls both, not two.
        return projectService.toView(root, true).toBuilder().affectedEvents(affectedEvents).build();
    }

    /**
     * Flags the events of every milestone in {@code changedMilestoneIds} — milestones this request
     * shrank below their allocations or moved to another currency — together, in one call, once the
     * whole tree has been applied and validated (so a request that ends up rejected flags nothing).
     * Returns the events flagged. Package-visible so {@code FundingBulkImportService} can apply the same
     * whole-group batching for CSV.
     */
    Either<ProblemDetail, List<FundingEventEntity>> flagEventsOfChangedMilestones(Set<String> changedMilestoneIds) {
        if (changedMilestoneIds.isEmpty()) {
            return Either.right(List.of());
        }
        return cascadeDeleteService.flagEventsAllocatedTo(changedMilestoneIds);
    }

    /** Appends {@code events} to {@code affected}, skipping any event already listed (one event can be hit by several changes in one request). */
    private static void addNewlyAffected(List<AffectedEventView> affected, List<AffectedEventView> events) {
        Set<String> seen = affected.stream().map(AffectedEventView::getEventId).collect(java.util.stream.Collectors.toSet());
        events.stream().filter(event -> seen.add(event.getEventId())).forEach(affected::add);
    }

    /**
     * Whether any PUBLISHED event exists anywhere in {@code project}'s own subtree — the same
     * unconditional lock {@code ProjectService#updateProject}/{@code MilestoneService#update} each
     * apply to their own narrower scope. Package-visible so {@code FundingBulkImportService} can apply
     * the identical check when it bypasses those methods for the same ordering reasons this class
     * exists for (see the class Javadoc).
     */
    boolean isLockedByPublishedEvent(ProjectEntity project) {
        Set<String> subtreeProjectIds = ProjectTreeSupport.subtreeProjectIds(projectRepository, project.getId());
        return allocationRepository.existsByMilestoneProjectIdInAndEventStatus(subtreeProjectIds, EventStatus.PUBLISHED);
    }

    /** Applies title/total/currency on the root itself — the same independent checks the narrow update endpoint runs, minus the sibling-total check (deferred to the whole-tree pass). */
    Optional<ProblemDetail> applyRootFields(ProjectEntity root, ProjectWithMilestonesCreateRequest request, Set<String> changedMilestoneIds) {
        boolean titleChanging = request.getProjectTitle() != null && !request.getProjectTitle().equals(root.getProjectTitle());
        if (titleChanging && projectRepository.existsByOrganisationIdAndProjectTitleAndParentProjectIsNullAndIdNot(
                root.getOrganisationId(), request.getProjectTitle(), root.getId())) {
            return Optional.of(Problems.conflict(
                    "Project title already exists in this organisation: " + request.getProjectTitle(),
                    ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS));
        }
        if (request.getTotalAmount() != null) {
            Optional<ProblemDetail> amountProblem = FundingValidations.projectAmount(request.getTotalAmount());
            if (amountProblem.isPresent()) {
                return amountProblem;
            }
        }
        boolean currencyChanging = request.getCurrency() != null && !request.getCurrency().equals(root.getCurrency());
        if (currencyChanging) {
            Optional<ProblemDetail> currencyProblem = FundingValidations.currencyCode(request.getCurrency(),
                    milestoneService.isCurrencyRegisteredAndActive(root.getOrganisationId(), request.getCurrency()));
            if (currencyProblem.isPresent()) {
                return currencyProblem;
            }
        }
        if (titleChanging) {
            root.setProjectTitle(request.getProjectTitle());
        }
        if (request.getTotalAmount() != null) {
            root.setTotalAmount(request.getTotalAmount());
        }
        if (currencyChanging) {
            // Every milestone about to move to another currency leaves its events booked in the old one —
            // collected here, flagged together with the rest at the end of the request.
            milestoneRepository.findByProjectIdIn(ProjectTreeSupport.subtreeProjectIds(projectRepository, root.getId())).stream()
                    .filter(milestone -> !request.getCurrency().equals(milestone.getCurrency()))
                    .forEach(milestone -> changedMilestoneIds.add(milestone.getId()));
            // Cascades to the whole subtree itself (mirrors ProjectService#updateProject) — a
            // milestone's/sub-project's currency always mirrors its owning root's.
            projectService.cascadeCurrency(root, request.getCurrency());
        } else {
            projectRepository.saveAndFlush(root);
        }
        return Optional.empty();
    }

    /**
     * Matches and applies every milestone/sub-project under {@code project}, recursively — proId first,
     * falling back to title, creating a new node when neither matches (same pattern used everywhere
     * else in this codebase). Records every project id touched, for the final coverage pass.
     */
    private Optional<ProblemDetail> applyChildren(ProjectEntity project,
            List<MilestoneCreateRequest> milestoneRequests, List<ProjectTreeNodeRequest> subProjectRequests,
            Set<String> touchedProjectIds, Set<String> changedMilestoneIds, List<AffectedEventView> affectedEvents) {

        touchedProjectIds.add(project.getId());

        // Same pair of guards as for sub-projects below (see that pair's Javadoc for the reasoning behind
        // each): a duplicate title among create/update nodes would otherwise have the second node
        // silently match-and-overwrite the milestone the first node just created or updated instead of
        // being rejected or creating a genuinely separate one; a duplicate proId on any mix of nodes
        // (e.g. one update node and one DELETE node both naming the same milestone) is always ambiguous.
        Optional<String> duplicateMilestoneTitle = FundingValidations.firstDuplicate(
                milestoneRequests.stream()
                        .filter(request -> !isDelete(request.getAction()))
                        .map(MilestoneCreateRequest::getMilestoneTitle)
                        .toList());
        if (duplicateMilestoneTitle.isPresent()) {
            return Optional.of(Problems.conflict(
                    "Duplicate milestone title under the same project: " + duplicateMilestoneTitle.get(),
                    ErrorTitleConstants.MILESTONE_TITLE_ALREADY_EXISTS));
        }
        Optional<String> duplicateMilestoneProId = FundingValidations.firstDuplicate(
                milestoneRequests.stream()
                        .map(MilestoneCreateRequest::getProId)
                        .filter(proId -> proId != null && !proId.isBlank())
                        .toList());
        if (duplicateMilestoneProId.isPresent()) {
            return Optional.of(Problems.conflict(
                    "Milestone id referenced more than once in the same request: " + duplicateMilestoneProId.get(),
                    ErrorTitleConstants.MILESTONE_PROID_ALREADY_EXISTS));
        }

        for (MilestoneCreateRequest milestoneRequest : milestoneRequests) {
            Optional<ProblemDetail> problem = isDelete(milestoneRequest.getAction())
                    ? deleteMilestoneNode(project, milestoneRequest, affectedEvents)
                    : applyMilestone(project, milestoneRequest, changedMilestoneIds);
            if (problem.isPresent()) {
                return problem;
            }
        }

        Optional<String> duplicateSubTitle = FundingValidations.firstDuplicate(
                subProjectRequests.stream()
                        .filter(node -> !isDelete(node.getAction()))
                        .map(ProjectTreeNodeRequest::getProjectTitle)
                        .toList());
        if (duplicateSubTitle.isPresent()) {
            return Optional.of(Problems.conflict(
                    "Duplicate sub-project title under the same parent: " + duplicateSubTitle.get(),
                    ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS));
        }

        // Unlike the title check above, this one is not scoped to non-delete nodes: a proId names one
        // specific existing sub-project, so it can never legitimately appear on two nodes in the same
        // request regardless of what those nodes each do (update, delete, or a mix) — that combination
        // is always an ambiguous request, not a valid rename-and-recreate pattern (which the title check
        // above exists to allow: deleting a sub-project and creating an unrelated new one with the same
        // title is fine, since a brand-new node never carries a proId).
        Optional<String> duplicateSubProId = FundingValidations.firstDuplicate(
                subProjectRequests.stream()
                        .map(ProjectTreeNodeRequest::getProId)
                        .filter(proId -> proId != null && !proId.isBlank())
                        .toList());
        if (duplicateSubProId.isPresent()) {
            return Optional.of(Problems.conflict(
                    "Sub-project id referenced more than once in the same request: " + duplicateSubProId.get(),
                    ErrorTitleConstants.PROJECT_PROID_ALREADY_EXISTS));
        }

        for (ProjectTreeNodeRequest node : subProjectRequests) {
            Optional<ProblemDetail> problem = isDelete(node.getAction())
                    ? deleteSubProjectNode(project, node, affectedEvents)
                    : applySubProjectNode(project, node, touchedProjectIds, changedMilestoneIds, affectedEvents);
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    private static boolean isDelete(String action) {
        return "DELETE".equalsIgnoreCase(action);
    }

    /**
     * Deletes an existing milestone named by this node (proId, falling back to title) via
     * {@link FundingCascadeDeleteService#deleteMilestone} — same PUBLISHED-block / non-published
     * detach-and-flag behavior as the standalone milestone-delete endpoint (LOB-2365 follow-up: "add
     * deletion to the tree-update PUT payload"). The whole-subtree PUBLISHED lock already checked
     * up front in {@link #updateWithMilestones} covers this project, so no separate lock check is
     * needed here. Events flagged as a side effect are appended to {@code affectedEvents}, so the whole
     * tree update's response can report every event affected anywhere in the request in one place.
     */
    private Optional<ProblemDetail> deleteMilestoneNode(ProjectEntity project, MilestoneCreateRequest request,
            List<AffectedEventView> affectedEvents) {
        Optional<MilestoneEntity> existing = findExistingMilestone(project, request);
        if (existing.isEmpty()) {
            return Optional.of(Problems.milestoneNotFound(
                    request.getProId() != null && !request.getProId().isBlank() ? request.getProId() : request.getMilestoneTitle()));
        }
        Either<ProblemDetail, List<FundingEventEntity>> result = cascadeDeleteService.deleteMilestone(existing.get());
        if (result.isLeft()) {
            return Optional.of(result.getLeft());
        }
        affectedEvents.addAll(FundingCascadeDeleteService.toAffectedEventViews(result.get()));
        return Optional.empty();
    }

    /**
     * Deletes an existing sub-project named by this node (proId, falling back to title) and its entire
     * subtree via {@link FundingCascadeDeleteService#deleteProjectSubtree} — the node's own
     * {@code milestones}/{@code subProjects} lists, if any, are ignored: deleting a node always removes
     * everything below it, regardless of what the request additionally describes there. Events flagged
     * as a side effect are appended to {@code affectedEvents} (see {@link #deleteMilestoneNode}).
     */
    private Optional<ProblemDetail> deleteSubProjectNode(ProjectEntity project, ProjectTreeNodeRequest node,
            List<AffectedEventView> affectedEvents) {
        Optional<ProjectEntity> existing = findExistingSubProject(project, node);
        if (existing.isEmpty()) {
            return Optional.of(Problems.subProjectReferenceNotFound(project.getProjectTitle(), node.getProjectTitle()));
        }
        Either<ProblemDetail, List<FundingEventEntity>> result = cascadeDeleteService.deleteProjectSubtree(existing.get());
        if (result.isLeft()) {
            return Optional.of(result.getLeft());
        }
        affectedEvents.addAll(FundingCascadeDeleteService.toAffectedEventViews(result.get()));
        return Optional.empty();
    }

    private Optional<ProblemDetail> applySubProjectNode(ProjectEntity project, ProjectTreeNodeRequest node,
            Set<String> touchedProjectIds, Set<String> changedMilestoneIds, List<AffectedEventView> affectedEvents) {
        Optional<ProblemDetail> nodeXor = FundingValidations.milestonesXorSubProjects(
                !node.getMilestones().isEmpty(), !node.getSubProjects().isEmpty());
        if (nodeXor.isPresent()) {
            return nodeXor;
        }

        Either<ProblemDetail, ProjectEntity> subProject = resolveOrCreateSubProject(project, node);
        if (subProject.isLeft()) {
            return Optional.of(subProject.getLeft());
        }

        return applyChildren(subProject.get(), node.getMilestones(), node.getSubProjects(), touchedProjectIds, changedMilestoneIds, affectedEvents);
    }

    /** Matches an existing sub-project by proId (falling back to title) and applies the node's fields to it, or creates a new one when neither matches. */
    private Either<ProblemDetail, ProjectEntity> resolveOrCreateSubProject(ProjectEntity parent, ProjectTreeNodeRequest node) {
        Optional<ProjectEntity> existing = findExistingSubProject(parent, node);
        if (existing.isEmpty()) {
            return projectStructureService.createSubProject(
                    parent, node.getProjectTitle(), node.getProId(), node.getFundingId(), node.getTotalAmount(), node.getCurrency());
        }
        ProjectEntity subProject = existing.get();
        Optional<ProblemDetail> problem = applySubProjectFields(subProject, node);
        return problem.isPresent() ? Either.left(problem.get()) : Either.right(subProject);
    }

    private Optional<ProjectEntity> findExistingSubProject(ProjectEntity parent, ProjectTreeNodeRequest node) {
        if (node.getProId() != null && !node.getProId().isBlank()) {
            return projectRepository.findByParentProjectIdAndProId(parent.getId(), node.getProId());
        }
        if (node.getProjectTitle() != null) {
            return projectRepository.findByParentProjectIdAndProjectTitle(parent.getId(), node.getProjectTitle());
        }
        return Optional.empty();
    }

    private Optional<ProblemDetail> applyMilestone(ProjectEntity project, MilestoneCreateRequest request, Set<String> changedMilestoneIds) {
        Optional<MilestoneEntity> existing = findExistingMilestone(project, request);
        if (existing.isEmpty()) {
            // Unlike a sub-project node's proId (see ProjectTreeNodeRequest#proId's Javadoc — used as-is
            // on create there, by design), a milestone's is documented as always system-assigned on
            // creation through this JSON API and "ignored if no existing milestone matches" (see
            // MilestoneCreateRequest#proId's Javadoc) — so null is passed here regardless of what
            // request.getProId() carries, via the same 2-arg overload the narrow POST /milestones
            // endpoint uses. Passing it through, as this used to, both contradicted that documented
            // contract and, worse, let a client-chosen value collide with a milestone deleted earlier
            // from this same project, silently reattaching its dangling allocations (see
            // MilestoneService#validateAndSave's existsById_MilestoneId check, which only reliably
            // protects the explicit-proId (CSV) path if this path never supplies one either).
            Either<ProblemDetail, MilestoneEntity> created = milestoneService.create(project.getId(), request);
            return created.isLeft() ? Optional.of(created.getLeft()) : Optional.empty();
        }
        return applyExistingMilestone(project, existing.get(), request, changedMilestoneIds);
    }

    private Optional<MilestoneEntity> findExistingMilestone(ProjectEntity project, MilestoneCreateRequest request) {
        if (request.getProId() != null && !request.getProId().isBlank()) {
            return milestoneRepository.findByProjectIdAndProId(project.getId(), request.getProId());
        }
        if (request.getMilestoneTitle() != null) {
            return milestoneRepository.findByProjectIdAndMilestoneTitle(project.getId(), request.getMilestoneTitle());
        }
        return Optional.empty();
    }

    /**
     * Applies a matched, already-existing milestone's field changes — reusing
     * {@code MilestoneService}'s own lock/title-conflict/apply logic exactly as {@code MilestoneService#update}
     * does (package-visible for this — see that class's Javadoc), minus only
     * {@code FundingValidations#milestone}'s parent-fit half, deliberately deferred to the whole-tree
     * coverage pass in {@link #updateWithMilestones}. Flagging events is deferred too, for the
     * same reason: a milestone shrunk below what's allocated to it, or moved to another currency, is
     * added to {@code changedMilestoneIds} instead of flagging immediately, so every such milestone is
     * flagged together in one call at the end, and only if the whole request is accepted (see
     * {@code MilestoneService#handleEventInvalidatingChange}).
     *
     * <p>Package-visible so {@code FundingBulkImportService} can apply the identical logic for an
     * existing CSV milestone row, instead of going through {@code MilestoneService#update} (whose
     * embedded parent-fit check has the same stale-sibling problem this whole class exists to avoid —
     * see the class Javadoc — for a CSV group touching more than one milestone under the same project).
     */
    Optional<ProblemDetail> applyExistingMilestone(ProjectEntity project, MilestoneEntity milestone,
            MilestoneCreateRequest request, Set<String> changedMilestoneIds) {
        boolean titleChanging = request.getMilestoneTitle() != null && !request.getMilestoneTitle().equals(milestone.getMilestoneTitle());
        MilestoneUpdateRequest updateRequest = MilestoneUpdateRequest.builder()
                .milestoneTitle(request.getMilestoneTitle())
                .description(request.getDescription())
                .milestoneAmount(request.getMilestoneAmount())
                .currency(request.getCurrency())
                .milestoneDate(request.getMilestoneDate())
                .build();

        Optional<ProblemDetail> lockProblem = milestoneService.checkFieldLock(milestone.getId(), updateRequest, titleChanging)
                .or(() -> milestoneService.checkCurrencyLock(project, milestone, updateRequest))
                .or(() -> milestoneService.checkTitleConflict(project, milestone.getId(), updateRequest, titleChanging));
        if (lockProblem.isPresent()) {
            return lockProblem;
        }

        // Positivity is independent of the parent-fit half of FundingValidations#milestone (deliberately
        // deferred to the whole-tree pass) — no reason to skip it too.
        Optional<ProblemDetail> amountProblem = FundingValidations.milestoneAmountPositive(request.getMilestoneAmount());
        if (amountProblem.isPresent()) {
            return amountProblem;
        }
        boolean currencyChanging = request.getCurrency() != null && !request.getCurrency().equals(milestone.getCurrency());
        if (currencyChanging) {
            Optional<ProblemDetail> currencyProblem = FundingValidations.currencyCode(request.getCurrency(),
                    milestoneService.isCurrencyRegisteredAndActive(project.getOrganisationId(), request.getCurrency()));
            if (currencyProblem.isPresent()) {
                return currencyProblem;
            }
        }

        if (milestoneService.invalidatesEvents(milestone, updateRequest)) {
            changedMilestoneIds.add(milestone.getId());
        }

        milestoneService.applyChanges(milestone, updateRequest, titleChanging);
        milestoneRepository.saveAndFlush(milestone);
        return Optional.empty();
    }

    Optional<ProblemDetail> applySubProjectFields(ProjectEntity subProject, ProjectTreeNodeRequest node) {
        boolean titleChanging = node.getProjectTitle() != null && !node.getProjectTitle().equals(subProject.getProjectTitle());
        if (titleChanging && projectRepository.existsByParentProjectIdAndProjectTitleAndIdNot(
                subProject.getParentProject().getId(), node.getProjectTitle(), subProject.getId())) {
            return Optional.of(Problems.conflict(
                    "Sub-project title already exists under this parent: " + node.getProjectTitle(),
                    ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS));
        }
        if (node.getTotalAmount() != null) {
            Optional<ProblemDetail> amountProblem = FundingValidations.projectAmount(node.getTotalAmount());
            if (amountProblem.isPresent()) {
                return amountProblem;
            }
        }
        if (titleChanging) {
            subProject.setProjectTitle(node.getProjectTitle());
        }
        if (node.getTotalAmount() != null) {
            subProject.setTotalAmount(node.getTotalAmount());
        }
        // Currency is never set independently on a sub-project — it always mirrors its root's (cascaded
        // from applyRootFields/ProjectService#cascadeCurrency), so a node-level currency override here
        // would just be silently out of sync with the rest of the tree; not accepted.
        projectRepository.saveAndFlush(subProject);
        return Optional.empty();
    }

    /**
     * Final, whole-tree structural pass: for every project touched by this update, its (now final)
     * totalAmount must cover the (now final) sum of its own milestones or sub-projects — using only
     * post-walk, freshly re-read values, never a value read mid-walk. A hard reject here rolls the
     * entire update back; nothing is partially saved.
     */
    Optional<ProblemDetail> validateWholeTreeCoverage(Set<String> touchedProjectIds) {
        for (String projectId : touchedProjectIds) {
            // A project can be touched (added here) by one node in the request and then deleted by a
            // different node later in the same request — normally prevented up front by
            // #applyChildren's duplicate-proId guard, but skipped here defensively rather than crashing
            // (orElseThrow) if that guard is ever bypassed: a project that no longer exists trivially
            // has nothing left to cover.
            Optional<ProjectEntity> projectM = projectRepository.findById(projectId);
            if (projectM.isEmpty()) {
                continue;
            }
            ProjectEntity project = projectM.get();
            Optional<ProblemDetail> coverage = FundingValidations.projectTotalCoversChildren(
                    project.getTotalAmount(),
                    FundingValidations.sumMilestoneAmounts(milestoneService.findByProjectId(projectId), null),
                    FundingValidations.sumProjectTotals(projectRepository.findByParentProjectId(projectId), null));
            if (coverage.isPresent()) {
                return coverage;
            }
        }
        return Optional.empty();
    }

    private void rollbackOnly() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

}
