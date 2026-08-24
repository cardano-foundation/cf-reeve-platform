package org.cardanofoundation.lob.app.funding.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Pageable;
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
import org.cardanofoundation.lob.app.funding.domain.request.ProjectTreeNodeRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.funding.util.FundingValidations;
import org.cardanofoundation.lob.app.funding.util.Problems;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {

    private final FundingProjectRepository projectRepository;
    private final MilestoneService milestoneService;
    private final SpendingEventService spendingEventService;
    private final ProjectStructureService projectStructureService;
    private final EventMilestoneAllocationRepository allocationRepository;
    private final KeycloakSecurityHelper keycloakSecurityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;
    private final FundingCascadeDeleteService cascadeDeleteService;

    // -------------------------------------------------------------------------
    // View-returning API (used by the controller — carries the ProblemDetail)
    // -------------------------------------------------------------------------

    public PagedResponse<ProjectView> listProjects(String organisationId, Pageable pageable) {
        if (!keycloakSecurityHelper.canUserAccessOrg(organisationId)) {
            return PagedResponse.error(Problems.unauthorized());
        }
        if (organisationPublicApi.findByOrganisationId(organisationId).isEmpty()) {
            return PagedResponse.error(Problems.organisationNotFound(organisationId));
        }
        return PagedResponse.of(projectRepository.findByOrganisationId(organisationId, pageable), this::toView);
    }

    public ProjectView getProject(String projectId) {
        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);
        if (projectM.isEmpty()) {
            return ProjectView.error(Problems.projectNotFound(projectId));
        }
        if (!keycloakSecurityHelper.canUserAccessOrg(projectM.get().getOrganisationId())) {
            return ProjectView.error(Problems.unauthorized());
        }
        // Get-by-id returns the full detail: milestones, sub-projects and the associated events.
        return toView(projectM.get(), true);
    }

    public PagedResponse<ProjectView> listSubProjects(String parentProjectId, Pageable pageable) {
        Optional<ProjectEntity> parentM = projectRepository.findById(parentProjectId);
        if (parentM.isEmpty()) {
            return PagedResponse.error(Problems.projectNotFound(parentProjectId));
        }
        if (!keycloakSecurityHelper.canUserAccessOrg(parentM.get().getOrganisationId())) {
            return PagedResponse.error(Problems.unauthorized());
        }
        return PagedResponse.of(projectRepository.findByParentProjectId(parentProjectId, pageable), this::toView);
    }

    /**
     * Creates a project together with its milestones <em>or</em> its sub-project tree (each node again
     * having milestones or sub-projects), in one atomic call. Any validation failure rolls the whole
     * tree back. The milestones-XOR-sub-projects rule and every amount/date validation apply per node.
     */
    @Transactional
    public ProjectView createWithMilestones(ProjectWithMilestonesCreateRequest request) {
        Optional<ProblemDetail> xor = FundingValidations.milestonesXorSubProjects(
                !request.getMilestones().isEmpty(), !request.getSubProjects().isEmpty());
        if (xor.isPresent()) {
            return ProjectView.error(xor.get());
        }

        // When a parentProjectId is supplied, create the project as a sub-project of that (existing)
        // parent — through the same shared creation path the event-allocation flow uses.
        Either<ProblemDetail, ProjectEntity> created = request.getParentProjectId() != null
                ? resolveParent(request).flatMap(parent -> projectStructureService.createSubProject(
                        parent, request.getProjectTitle(),
                        request.getFundingId(), request.getTotalAmount(), request.getCurrency()))
                : createRootProject(request);
        if (created.isLeft()) {
            return ProjectView.error(created.getLeft());
        }

        ProjectEntity projectEntity = created.get();
        Optional<ProblemDetail> childrenProblem = createNodeChildren(
                projectEntity, request.getMilestones(), request.getSubProjects());
        if (childrenProblem.isPresent()) {
            // Keep creation atomic: roll the whole tree back on any failure.
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            return ProjectView.error(childrenProblem.get());
        }
        return toView(projectEntity);
    }

    private Either<ProblemDetail, ProjectEntity> createRootProject(ProjectWithMilestonesCreateRequest request) {
        // Unlike a sub-project (which defaults to its parent's currency — see
        // ProjectStructureService.createSubProject), a root project has no parent to inherit from, so
        // its currency must be given explicitly. The DTO itself no longer enforces this via @NotBlank
        // since the same field is also used for sub-project creation, where it's optional.
        if (request.getCurrency() == null || request.getCurrency().isBlank()) {
            return Either.left(Problems.badRequest(
                    "Currency is required to create a root project: " + request.getProjectTitle(), ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }
        Optional<ProblemDetail> currencyProblem = FundingValidations.currencyCode(request.getCurrency());
        if (currencyProblem.isPresent()) {
            return Either.left(currencyProblem.get());
        }
        Optional<ProblemDetail> amountProblem = FundingValidations.projectAmount(request.getTotalAmount());
        if (amountProblem.isPresent()) {
            return Either.left(amountProblem.get());
        }
        if (projectRepository.existsByOrganisationIdAndProjectTitleAndParentProjectIsNull(
                request.getOrganisationId(), request.getProjectTitle())) {
            return Either.left(Problems.conflict(
                    "Project title already exists in this organisation: " + request.getProjectTitle(),
                    ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS));
        }
        Optional<ProblemDetail> fundingIdProblem = projectStructureService.fundingIdAvailable(
                request.getOrganisationId(), request.getFundingId());
        if (fundingIdProblem.isPresent()) {
            return Either.left(fundingIdProblem.get());
        }
        String projectId = ProjectEntity.id(request.getOrganisationId(), request.getProjectTitle());
        return Either.right(projectRepository.saveAndFlush(toEntity(request, projectId)));
    }

    /** The parent for a project created as a sub-project: must exist and belong to the same organisation. */
    private Either<ProblemDetail, ProjectEntity> resolveParent(ProjectWithMilestonesCreateRequest request) {
        Optional<ProjectEntity> parentM = projectRepository.findById(request.getParentProjectId());
        if (parentM.isEmpty()) {
            return Either.left(Problems.notFound(
                    "Parent project not found: " + request.getParentProjectId(), ErrorTitleConstants.PARENT_PROJECT_NOT_FOUND));
        }
        ProjectEntity parent = parentM.get();
        if (!parent.getOrganisationId().equals(request.getOrganisationId())) {
            return Either.left(Problems.badRequest(
                    "Parent project %s belongs to a different organisation".formatted(request.getParentProjectId()),
                    ErrorTitleConstants.PARENT_PROJECT_ORG_MISMATCH));
        }
        return Either.right(parent);
    }

    /**
     * Creates the children of {@code project}: either its milestones or its sub-projects (recursively).
     * Returns the first validation failure, or empty when the whole subtree was created.
     */
    private Optional<ProblemDetail> createNodeChildren(ProjectEntity project,
            List<MilestoneCreateRequest> milestones, List<ProjectTreeNodeRequest> subProjects) {

        for (MilestoneCreateRequest milestoneRequest : milestones) {
            Either<ProblemDetail, MilestoneEntity> result = milestoneService.create(project.getId(), milestoneRequest);
            if (result.isLeft()) {
                return Optional.of(result.getLeft());
            }
        }

        // Sub-project titles are unique within their parent — reject duplicate titles among the sibling
        // nodes of this request up front (a per-row DB check alone can miss same-request siblings).
        Optional<String> duplicateSubTitle = FundingValidations.firstDuplicate(
                subProjects.stream().map(ProjectTreeNodeRequest::getProjectTitle).toList());
        if (duplicateSubTitle.isPresent()) {
            return Optional.of(Problems.conflict(
                    "Duplicate sub-project title under the same parent: " + duplicateSubTitle.get(),
                    ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS));
        }

        for (ProjectTreeNodeRequest node : subProjects) {
            Optional<ProblemDetail> nodeXor = FundingValidations.milestonesXorSubProjects(
                    !node.getMilestones().isEmpty(), !node.getSubProjects().isEmpty());
            if (nodeXor.isPresent()) {
                return nodeXor;
            }
            Either<ProblemDetail, ProjectEntity> subProject = projectStructureService.createSubProject(
                    project, node.getProjectTitle(),
                    node.getFundingId(), node.getTotalAmount(), node.getCurrency());
            if (subProject.isLeft()) {
                return Optional.of(subProject.getLeft());
            }

            Optional<ProblemDetail> childProblem = createNodeChildren(
                    subProject.get(), node.getMilestones(), node.getSubProjects());
            if (childProblem.isPresent()) {
                return childProblem;
            }
        }
        return Optional.empty();
    }

    @Transactional
    public ProjectView updateProject(String projectId, ProjectUpdateRequest request) {
        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);
        if (projectM.isEmpty()) {
            return ProjectView.error(Problems.projectNotFound(projectId));
        }
        ProjectEntity project = projectM.get();
        if (!keycloakSecurityHelper.canUserAccessOrg(project.getOrganisationId())) {
            return ProjectView.error(Problems.unauthorized());
        }
        // Locked when the project or any descendant sub-project owns a milestone tied to a published event.
        if (allocationRepository.existsByMilestoneProjectIdInAndEventStatus(
                ProjectTreeSupport.subtreeProjectIds(projectRepository, projectId), EventStatus.PUBLISHED)) {
            return ProjectView.error(Problems.conflict(
                    "Cannot update project linked to a published event: %s".formatted(projectId),
                    ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED));
        }
        Optional<ProblemDetail> amountProblem = FundingValidations.projectAmount(request.getTotalAmount());
        if (amountProblem.isPresent()) {
            return ProjectView.error(amountProblem.get());
        }
        Optional<ProblemDetail> currencyProblem = FundingValidations.currencyCode(request.getCurrency());
        if (currencyProblem.isPresent()) {
            return ProjectView.error(currencyProblem.get());
        }

        // The budget the project ends up with — parent-fit and child-coverage checks validate this value.
        BigDecimal effectiveTotal = request.getTotalAmount() != null ? request.getTotalAmount() : project.getTotalAmount();

        if (request.getTotalAmount() != null) {
            // Shrinking the budget must not leave already-planned children uncovered.
            Optional<ProblemDetail> coverage = FundingValidations.projectTotalCoversChildren(
                    effectiveTotal,
                    FundingValidations.sumMilestoneAmounts(milestoneService.findByProjectId(projectId), null),
                    FundingValidations.sumProjectTotals(projectRepository.findByParentProjectId(projectId), null));
            if (coverage.isPresent()) {
                return ProjectView.error(coverage.get());
            }
            // A sub-project's new budget must still fit its (unchanged) parent.
            if (request.getParentProjectId() == null && project.getParentProject() != null) {
                ProjectEntity parent = project.getParentProject();
                BigDecimal otherSubProjectsTotal = FundingValidations.sumProjectTotals(
                        projectRepository.findByParentProjectId(parent.getId()), project.getId());
                Optional<ProblemDetail> fit = FundingValidations.subProjectAmount(effectiveTotal, parent, otherSubProjectsTotal);
                if (fit.isPresent()) {
                    return ProjectView.error(fit.get());
                }
            }
        }

        if (request.getParentProjectId() != null) {
            Optional<ProblemDetail> parentProblem = assignParent(project, request.getParentProjectId(), effectiveTotal);
            if (parentProblem.isPresent()) {
                return ProjectView.error(parentProblem.get());
            }
        }
        // projectTitle is immutable — the project's id is derived from it, so changing it would leave
        // the id stale relative to its new title.
        if (request.getProjectTitle() != null && !request.getProjectTitle().equals(project.getProjectTitle())) {
            return ProjectView.error(Problems.badRequest(
                    "projectTitle cannot be changed on update (id is derived from it)",
                    ErrorTitleConstants.PROJECT_TITLE_IMMUTABLE));
        }
        // A re-parent can still collide with a same-named sibling under the new parent, even though
        // the title itself doesn't change.
        if (request.getParentProjectId() != null) {
            Optional<ProblemDetail> titleConflict = projectTitleConflict(project, project.getProjectTitle());
            if (titleConflict.isPresent()) {
                return ProjectView.error(titleConflict.get());
            }
        }
        if (request.getTotalAmount() != null) project.setTotalAmount(request.getTotalAmount());
        if (request.getCurrency() != null) project.setCurrency(request.getCurrency());
        return toView(projectRepository.saveAndFlush(project));
    }

    /**
     * Attaches {@code project} under {@code parentProjectId} as a sub-project. The parent must exist,
     * belong to the same organisation, and assigning it must not introduce a cycle (i.e. the parent
     * may not be the project itself or one of its descendants). {@code effectiveTotal} is the budget
     * the project ends up with (an updated amount from the same request wins over the stored one).
     */
    private Optional<ProblemDetail> assignParent(ProjectEntity project, String parentProjectId, BigDecimal effectiveTotal) {
        Optional<ProjectEntity> parentM = projectRepository.findById(parentProjectId);
        if (parentM.isEmpty()) {
            return Optional.of(Problems.notFound(
                    "Parent project not found: " + parentProjectId, ErrorTitleConstants.PARENT_PROJECT_NOT_FOUND));
        }
        ProjectEntity parent = parentM.get();
        if (!parent.getOrganisationId().equals(project.getOrganisationId())) {
            return Optional.of(Problems.badRequest(
                    "Parent project %s belongs to a different organisation".formatted(parentProjectId),
                    ErrorTitleConstants.PARENT_PROJECT_ORG_MISMATCH));
        }
        if (createsCycle(project.getId(), parent)) {
            return Optional.of(Problems.badRequest(
                    "Assigning parent %s to project %s would create a circular dependency".formatted(parentProjectId, project.getId()),
                    ErrorTitleConstants.PROJECT_CIRCULAR_DEPENDENCY));
        }
        Optional<ProblemDetail> structure = FundingValidations.subProjectAllowed(milestoneService.hasMilestones(parent.getId()));
        if (structure.isPresent()) {
            return structure;
        }
        BigDecimal otherSubProjectsTotal = FundingValidations.sumProjectTotals(
                projectRepository.findByParentProjectId(parent.getId()), project.getId());
        Optional<ProblemDetail> amountProblem = FundingValidations.subProjectAmount(
                effectiveTotal, parent, otherSubProjectsTotal);
        if (amountProblem.isPresent()) {
            return amountProblem;
        }
        project.setParentProject(parent);
        return Optional.empty();
    }

    /**
     * A project title must be unique within its scope: root projects per organisation, sub-projects
     * within their parent. The check excludes the project itself so an unchanged title never conflicts.
     */
    private Optional<ProblemDetail> projectTitleConflict(ProjectEntity project, String title) {
        boolean exists = project.getParentProject() == null
                ? projectRepository.existsByOrganisationIdAndProjectTitleAndParentProjectIsNullAndIdNot(
                        project.getOrganisationId(), title, project.getId())
                : projectRepository.existsByParentProjectIdAndProjectTitleAndIdNot(
                        project.getParentProject().getId(), title, project.getId());
        if (exists) {
            return Optional.of(Problems.conflict(
                    "Project title already exists in this scope: " + title,
                    ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS));
        }
        return Optional.empty();
    }

    /**
     * True when {@code projectId} already appears in the candidate parent's ancestor chain — which
     * includes the candidate parent being the project itself (self-parenting) or one of its
     * descendants. Walking up the single parent link terminates at a root project.
     */
    private static boolean createsCycle(String projectId, ProjectEntity candidateParent) {
        ProjectEntity cursor = candidateParent;
        while (cursor != null) {
            if (projectId.equals(cursor.getId())) {
                return true;
            }
            cursor = cursor.getParentProject();
        }
        return false;
    }

    @Transactional
    public Optional<ProblemDetail> deleteProject(String projectId) {
        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);
        if (projectM.isEmpty()) {
            return Optional.of(Problems.projectNotFound(projectId));
        }
        if (!keycloakSecurityHelper.canUserAccessOrg(projectM.get().getOrganisationId())) {
            return Optional.of(Problems.unauthorized());
        }
        // Cascade: fails when any published event is associated anywhere in the subtree; otherwise the
        // project, its sub-projects, milestones and the referencing draft-event allocations are removed.
        return cascadeDeleteService.deleteProjectSubtree(projectM.get());
    }

    // -------------------------------------------------------------------------
    // Internal lookups / mapping
    // -------------------------------------------------------------------------

    public Optional<ProjectEntity> findById(String projectId) {
        return projectRepository.findById(projectId);
    }

    public boolean existsByOrganisationIdAndExternalProjectId(String organisationId, String externalProjectId) {
        return projectRepository.existsByOrganisationIdAndExternalProjectId(organisationId, externalProjectId);
    }

    /** List/summary view — milestones and sub-projects, without the (heavier) associated events. */
    public ProjectView toView(ProjectEntity project) {
        return toView(project, false);
    }

    /**
     * Builds the project view. When {@code includeEvents} is set, each project node additionally
     * carries the events allocated to its milestones (used by get-by-id); list endpoints omit them.
     */
    private ProjectView toView(ProjectEntity project, boolean includeEvents) {
        List<MilestoneView> milestoneViews = milestoneService.findByProjectId(project.getId()).stream()
                .map(milestoneService::toView)
                .toList();

        List<ProjectView> subProjectViews = projectRepository.findByParentProjectId(project.getId()).stream()
                .map(subProject -> toView(subProject, includeEvents))
                .toList();

        String parentProjectId = project.getParentProject() != null ? project.getParentProject().getId() : null;

        // Calculated (not stored): the project's spend rolls up its milestones' and sub-projects' spend.
        BigDecimal spentAmount = sumSpent(milestoneViews.stream().map(MilestoneView::getSpentAmount))
                .add(sumSpent(subProjectViews.stream().map(ProjectView::getSpentAmount)));

        return ProjectView.builder()
                .projectId(project.getId())
                .organisationId(project.getOrganisationId())
                .fundingId(project.getFundingId())
                .externalProjectId(project.getExternalProjectId())
                .projectTitle(project.getProjectTitle())
                .totalAmount(project.getTotalAmount())
                .currency(project.getCurrency())
                .parentProjectId(parentProjectId)
                .createdAt(project.getCreatedAt())
                .milestones(milestoneViews)
                .subProjects(subProjectViews)
                .spentAmount(spentAmount)
                .events(includeEvents ? loadEvents(project.getId()) : null)
                .build();
    }

    private static BigDecimal sumSpent(java.util.stream.Stream<BigDecimal> amounts) {
        return amounts.filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** All events allocated to the given project's milestones, as views. */
    private List<SpendingEventView> loadEvents(String projectId) {
        return spendingEventService
                .findByProjectIdAndFilter(projectId, Optional.empty(), Optional.empty(), Pageable.unpaged())
                .getContent().stream()
                .map(spendingEventService::toView)
                .toList();
    }

    private ProjectEntity toEntity(ProjectWithMilestonesCreateRequest request, String projectId) {
        return ProjectEntity.builder()
                .id(projectId)
                .organisationId(request.getOrganisationId())
                .fundingId(request.getFundingId())
                .projectTitle(request.getProjectTitle())
                .totalAmount(request.getTotalAmount())
                .currency(request.getCurrency())
                .build();
    }

}
