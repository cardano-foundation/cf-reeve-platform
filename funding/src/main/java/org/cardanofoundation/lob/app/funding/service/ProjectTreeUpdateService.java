package org.cardanofoundation.lob.app.funding.service;

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

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectTreeNodeRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.funding.util.FundingValidations;
import org.cardanofoundation.lob.app.funding.util.Problems;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Updates an existing project's whole structure (itself, plus every sub-project and milestone under
 * it) in one atomic call — the update-side counterpart to {@link ProjectService#createWithMilestones}.
 * Reuses the exact same request shape ({@link ProjectWithMilestonesCreateRequest}), matched by
 * {@code proId} instead of an internal id (see {@link ProjectTreeNodeRequest#proId} /
 * {@link MilestoneCreateRequest#proId}), so a single PUT can resize the project and its children
 * together.
 *
 * <p>This exists because the narrow single-entity endpoints ({@code ProjectService#updateProject},
 * {@code MilestoneService#update}) each validate budget-fit against whatever is <em>currently
 * persisted</em> for a node's siblings — correct for editing one node in isolation, but wrong for
 * resizing several related levels in the same request: shrinking a parent before its children hits the
 * new hard block (see {@code FundingValidations#projectTotalCoversChildren}), and shrinking a child
 * before its parent hits the existing one ({@code FundingValidations#subProjectAmount}/{@code #milestone}).
 * This service instead applies every field value across the whole touched tree first (top-down, so a
 * parent's new value is already in memory before its children are processed), then runs one
 * consolidated structural pass over the whole touched tree afterward using only final values — never a
 * mid-walk, stale-sibling comparison.
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
    public ProjectView updateWithMilestones(ProjectWithMilestonesCreateRequest request) {
        if (request.getProId() == null || request.getProId().isBlank()) {
            return ProjectView.error(Problems.badRequest(
                    "proId is required to identify the project to update", ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }
        Optional<ProjectEntity> rootM = projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull(
                request.getOrganisationId(), request.getProId());
        if (rootM.isEmpty()) {
            return ProjectView.error(Problems.projectNotFound(request.getProId()));
        }
        ProjectEntity root = rootM.get();
        if (!keycloakSecurityHelper.canUserAccessOrg(root.getOrganisationId())) {
            return ProjectView.error(Problems.unauthorized());
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

        Optional<ProblemDetail> rootFieldsProblem = applyRootFields(root, request);
        if (rootFieldsProblem.isPresent()) {
            return ProjectView.error(rootFieldsProblem.get());
        }

        Set<String> touchedProjectIds = new LinkedHashSet<>();
        Set<String> shrunkMilestoneIds = new LinkedHashSet<>();
        Optional<ProblemDetail> childrenProblem = applyChildren(
                root, request.getMilestones(), request.getSubProjects(), touchedProjectIds, shrunkMilestoneIds);
        if (childrenProblem.isPresent()) {
            rollbackOnly();
            return ProjectView.error(childrenProblem.get());
        }

        Optional<ProblemDetail> coverage = validateWholeTreeCoverage(touchedProjectIds);
        if (coverage.isPresent()) {
            rollbackOnly();
            return ProjectView.error(coverage.get());
        }

        Optional<ProblemDetail> flagged = flagShrunkMilestones(shrunkMilestoneIds);
        if (flagged.isPresent()) {
            rollbackOnly();
            return ProjectView.error(flagged.get());
        }

        return projectService.toView(root);
    }

    /**
     * Flags every milestone in {@code shrunkMilestoneIds} together, in one call — never per-milestone,
     * immediately, as each is processed (see {@code MilestoneService#handleAmountShrink}'s Javadoc for
     * why: an event allocating to two milestones that are <em>both</em> being shrunk in this same
     * request must see both as "in scope" for the cross-project check, not just whichever one happens
     * to be processed first). Package-visible so {@code FundingBulkImportService} can apply the same
     * whole-group batching for CSV.
     */
    Optional<ProblemDetail> flagShrunkMilestones(Set<String> shrunkMilestoneIds) {
        if (shrunkMilestoneIds.isEmpty()) {
            return Optional.empty();
        }
        return cascadeDeleteService.markContainedEventsAsErrorOrBlock(shrunkMilestoneIds);
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
    Optional<ProblemDetail> applyRootFields(ProjectEntity root, ProjectWithMilestonesCreateRequest request) {
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
            Set<String> touchedProjectIds, Set<String> shrunkMilestoneIds) {

        touchedProjectIds.add(project.getId());

        for (MilestoneCreateRequest milestoneRequest : milestoneRequests) {
            Optional<ProblemDetail> problem = applyMilestone(project, milestoneRequest, shrunkMilestoneIds);
            if (problem.isPresent()) {
                return problem;
            }
        }

        Optional<String> duplicateSubTitle = FundingValidations.firstDuplicate(
                subProjectRequests.stream().map(ProjectTreeNodeRequest::getProjectTitle).toList());
        if (duplicateSubTitle.isPresent()) {
            return Optional.of(Problems.conflict(
                    "Duplicate sub-project title under the same parent: " + duplicateSubTitle.get(),
                    ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS));
        }

        for (ProjectTreeNodeRequest node : subProjectRequests) {
            Optional<ProblemDetail> problem = applySubProjectNode(project, node, touchedProjectIds, shrunkMilestoneIds);
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    private Optional<ProblemDetail> applySubProjectNode(ProjectEntity project, ProjectTreeNodeRequest node,
            Set<String> touchedProjectIds, Set<String> shrunkMilestoneIds) {
        Optional<ProblemDetail> nodeXor = FundingValidations.milestonesXorSubProjects(
                !node.getMilestones().isEmpty(), !node.getSubProjects().isEmpty());
        if (nodeXor.isPresent()) {
            return nodeXor;
        }

        Either<ProblemDetail, ProjectEntity> subProject = resolveOrCreateSubProject(project, node);
        if (subProject.isLeft()) {
            return Optional.of(subProject.getLeft());
        }

        return applyChildren(subProject.get(), node.getMilestones(), node.getSubProjects(), touchedProjectIds, shrunkMilestoneIds);
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

    private Optional<ProblemDetail> applyMilestone(ProjectEntity project, MilestoneCreateRequest request, Set<String> shrunkMilestoneIds) {
        Optional<MilestoneEntity> existing = findExistingMilestone(project, request);
        if (existing.isEmpty()) {
            Either<ProblemDetail, MilestoneEntity> created = milestoneService.create(project.getId(), request, request.getProId());
            return created.isLeft() ? Optional.of(created.getLeft()) : Optional.empty();
        }
        return applyExistingMilestone(project, existing.get(), request, shrunkMilestoneIds);
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
     * coverage pass in {@link #updateWithMilestones}. Scenario B (shrinking below what's already
     * allocated to this milestone) is deferred too, for the same reason: {@code milestoneId} is added to
     * {@code shrunkMilestoneIds} instead of flagging immediately, so every milestone shrunk anywhere in
     * this request can be flagged together in one call at the end — see
     * {@code MilestoneService#handleAmountShrink}'s Javadoc for why flagging immediately, one milestone
     * at a time, would wrongly cross-project-block an event that allocates to two milestones both being
     * shrunk in this same request.
     *
     * <p>Package-visible so {@code FundingBulkImportService} can apply the identical logic for an
     * existing CSV milestone row, instead of going through {@code MilestoneService#update} (whose
     * embedded parent-fit check has the same stale-sibling problem this whole class exists to avoid —
     * see the class Javadoc — for a CSV group touching more than one milestone under the same project).
     */
    Optional<ProblemDetail> applyExistingMilestone(ProjectEntity project, MilestoneEntity milestone,
            MilestoneCreateRequest request, Set<String> shrunkMilestoneIds) {
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

        if (milestoneService.needsErrorFlagging(milestone.getId(), request.getMilestoneAmount())) {
            shrunkMilestoneIds.add(milestone.getId());
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
            ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
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
