package org.cardanofoundation.lob.app.funding.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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

    private static final String PROJECT_NOT_FOUND_DETAIL = "Project not found: ";

    private final FundingProjectRepository projectRepository;
    private final MilestoneService milestoneService;
    private final SpendingEventService spendingEventService;
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
            return PagedResponse.error(Problems.of(HttpStatus.BAD_REQUEST,
                    "Organisation with id: %s not found".formatted(organisationId), "ORGANISATION_NOT_FOUND"));
        }
        return PagedResponse.of(projectRepository.findByOrganisationId(organisationId, pageable), this::toView);
    }

    public ProjectView getProject(String projectId) {
        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);
        if (projectM.isEmpty()) {
            return ProjectView.error(projectNotFound(projectId));
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
            return PagedResponse.error(projectNotFound(parentProjectId));
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
        Optional<ProblemDetail> amountProblem = FundingValidations.projectAmount(request.getTotalAmount());
        if (amountProblem.isPresent()) {
            return ProjectView.error(amountProblem.get());
        }
        Optional<ProblemDetail> xor = requireMilestonesXorSubProjects(request.getMilestones(), request.getSubProjects());
        if (xor.isPresent()) {
            return ProjectView.error(xor.get());
        }

        // When a parentProjectId is supplied, create the project as a sub-project of that (existing) parent.
        ProjectEntity parent = null;
        if (request.getParentProjectId() != null) {
            Either<ProblemDetail, ProjectEntity> parentResult = resolveParentForNewSubProject(request);
            if (parentResult.isLeft()) {
                return ProjectView.error(parentResult.getLeft());
            }
            parent = parentResult.get();
        }

        String projectId = parent != null
                ? ProjectEntity.subId(parent.getId(), request.getExternalProjectId())
                : ProjectEntity.id(request.getOrganisationId(), request.getExternalProjectId());
        boolean exists = parent != null
                ? projectRepository.existsById(projectId)
                : projectRepository.existsByOrganisationIdAndExternalProjectId(request.getOrganisationId(), request.getExternalProjectId());
        if (exists) {
            return ProjectView.error(Problems.conflict(
                    "Project already exists for externalProjectId: " + request.getExternalProjectId(),
                    ErrorTitleConstants.PROJECT_ALREADY_EXISTS));
        }

        ProjectEntity projectEntity = projectRepository.saveAndFlush(toEntity(request, projectId, parent));
        Optional<ProblemDetail> childrenProblem = createNodeChildren(
                projectEntity, request.getMilestones(), request.getSubProjects(), request.getOrganisationId());
        if (childrenProblem.isPresent()) {
            // Keep creation atomic: roll the whole tree back on any failure.
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            return ProjectView.error(childrenProblem.get());
        }
        return toView(projectEntity);
    }

    /**
     * Resolves and validates the parent for a project being created as a sub-project: the parent must
     * exist, belong to the same organisation, be allowed to hold sub-projects (no milestones of its own),
     * and the new sub-project's amount must fit within the parent's budget.
     */
    private Either<ProblemDetail, ProjectEntity> resolveParentForNewSubProject(ProjectWithMilestonesCreateRequest request) {
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
        Optional<ProblemDetail> structure = FundingValidations.subProjectAllowed(milestoneService.hasMilestones(parent.getId()));
        if (structure.isPresent()) {
            return Either.left(structure.get());
        }
        BigDecimal otherSubProjectsTotal = FundingValidations.sumProjectTotals(
                projectRepository.findByParentProjectId(parent.getId()), null);
        Optional<ProblemDetail> subAmount = FundingValidations.subProjectAmount(
                request.getTotalAmount(), parent, otherSubProjectsTotal);
        if (subAmount.isPresent()) {
            return Either.left(subAmount.get());
        }
        return Either.right(parent);
    }

    /**
     * Creates the children of {@code project}: either its milestones or its sub-projects (recursively).
     * Returns the first validation failure, or empty when the whole subtree was created.
     */
    private Optional<ProblemDetail> createNodeChildren(ProjectEntity project,
            List<MilestoneCreateRequest> milestones, List<ProjectTreeNodeRequest> subProjects, String organisationId) {

        for (MilestoneCreateRequest milestoneRequest : milestones) {
            Either<ProblemDetail, MilestoneEntity> result = milestoneService.create(project.getId(), milestoneRequest);
            if (result.isLeft()) {
                return Optional.of(result.getLeft());
            }
        }

        BigDecimal siblingsTotal = BigDecimal.ZERO;
        for (ProjectTreeNodeRequest node : subProjects) {
            Optional<ProblemDetail> nodeXor = requireMilestonesXorSubProjects(node.getMilestones(), node.getSubProjects());
            if (nodeXor.isPresent()) {
                return nodeXor;
            }
            Optional<ProblemDetail> amount = FundingValidations.projectAmount(node.getTotalAmount());
            if (amount.isPresent()) {
                return amount;
            }
            Optional<ProblemDetail> subAmount = FundingValidations.subProjectAmount(node.getTotalAmount(), project, siblingsTotal);
            if (subAmount.isPresent()) {
                return subAmount;
            }

            String subProjectId = ProjectEntity.subId(project.getId(), node.getExternalProjectId());
            if (projectRepository.existsById(subProjectId)) {
                return Optional.of(Problems.conflict(
                        "Sub-project already exists: " + node.getExternalProjectId(),
                        ErrorTitleConstants.PROJECT_ALREADY_EXISTS));
            }

            ProjectEntity subProject = projectRepository.saveAndFlush(ProjectEntity.builder()
                    .id(subProjectId)
                    .organisationId(organisationId)
                    .fundingId(node.getFundingId())
                    .externalProjectId(node.getExternalProjectId())
                    .projectTitle(node.getProjectTitle())
                    .totalAmount(node.getTotalAmount())
                    .currency(node.getCurrency())
                    .parentProject(project)
                    .build());

            if (node.getTotalAmount() != null) {
                siblingsTotal = siblingsTotal.add(node.getTotalAmount());
            }

            Optional<ProblemDetail> childProblem = createNodeChildren(
                    subProject, node.getMilestones(), node.getSubProjects(), organisationId);
            if (childProblem.isPresent()) {
                return childProblem;
            }
        }
        return Optional.empty();
    }

    /** A project node holds milestones or sub-projects, never both. */
    private Optional<ProblemDetail> requireMilestonesXorSubProjects(
            List<MilestoneCreateRequest> milestones, List<ProjectTreeNodeRequest> subProjects) {
        if (!milestones.isEmpty() && !subProjects.isEmpty()) {
            return Optional.of(Problems.badRequest(
                    "A project has either milestones or sub-projects, not both",
                    ErrorTitleConstants.SUBPROJECT_NOT_ALLOWED_WITH_MILESTONES));
        }
        return Optional.empty();
    }

    @Transactional
    public ProjectView updateProject(String projectId, ProjectUpdateRequest request) {
        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);
        if (projectM.isEmpty()) {
            return ProjectView.error(projectNotFound(projectId));
        }
        ProjectEntity project = projectM.get();
        if (!keycloakSecurityHelper.canUserAccessOrg(project.getOrganisationId())) {
            return ProjectView.error(Problems.unauthorized());
        }
        if (allocationRepository.existsByMilestoneProjectIdAndEventStatus(projectId, EventStatus.PUBLISHED)) {
            return ProjectView.error(Problems.conflict(
                    "Cannot update project linked to a published event: %s".formatted(projectId),
                    ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED));
        }
        Optional<ProblemDetail> amountProblem = FundingValidations.projectAmount(request.getTotalAmount());
        if (amountProblem.isPresent()) {
            return ProjectView.error(amountProblem.get());
        }
        if (request.getParentProjectId() != null) {
            Optional<ProblemDetail> parentProblem = assignParent(project, request.getParentProjectId());
            if (parentProblem.isPresent()) {
                return ProjectView.error(parentProblem.get());
            }
        }
        if (request.getProjectTitle() != null) project.setProjectTitle(request.getProjectTitle());
        if (request.getTotalAmount() != null) project.setTotalAmount(request.getTotalAmount());
        if (request.getCurrency() != null) project.setCurrency(request.getCurrency());
        return toView(projectRepository.saveAndFlush(project));
    }

    /**
     * Attaches {@code project} under {@code parentProjectId} as a sub-project. The parent must exist,
     * belong to the same organisation, and assigning it must not introduce a cycle (i.e. the parent
     * may not be the project itself or one of its descendants).
     */
    private Optional<ProblemDetail> assignParent(ProjectEntity project, String parentProjectId) {
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
                project.getTotalAmount(), parent, otherSubProjectsTotal);
        if (amountProblem.isPresent()) {
            return amountProblem;
        }
        project.setParentProject(parent);
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
            return Optional.of(projectNotFound(projectId));
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

    private ProjectEntity toEntity(ProjectWithMilestonesCreateRequest request, String projectId, ProjectEntity parent) {
        return ProjectEntity.builder()
                .id(projectId)
                .organisationId(request.getOrganisationId())
                .fundingId(request.getFundingId())
                .externalProjectId(request.getExternalProjectId())
                .projectTitle(request.getProjectTitle())
                .totalAmount(request.getTotalAmount())
                .currency(request.getCurrency())
                .parentProject(parent)
                .build();
    }

    private static ProblemDetail projectNotFound(String projectId) {
        return Problems.notFound(PROJECT_NOT_FOUND_DETAIL + projectId, ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

}
