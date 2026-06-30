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
import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
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
    private final EventMilestoneAllocationRepository allocationRepository;
    private final KeycloakSecurityHelper keycloakSecurityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;

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
        return toView(projectM.get());
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

    @Transactional
    public ProjectView createWithMilestones(ProjectWithMilestonesCreateRequest request) {
        Optional<ProblemDetail> amountProblem = FundingValidations.projectAmount(request.getTotalAmount());
        if (amountProblem.isPresent()) {
            return ProjectView.error(amountProblem.get());
        }
        if (projectRepository.existsByOrganisationIdAndExternalProjectId(request.getOrganisationId(), request.getExternalProjectId())) {
            return ProjectView.error(Problems.conflict(
                    "Project already exists for externalProjectId: " + request.getExternalProjectId(),
                    ErrorTitleConstants.PROJECT_ALREADY_EXISTS));
        }
        ProjectEntity projectEntity = projectRepository.saveAndFlush(toEntity(request));
        for (MilestoneCreateRequest milestoneRequest : request.getMilestones()) {
            Either<ProblemDetail, MilestoneEntity> result = milestoneService.create(projectEntity.getId(), milestoneRequest);
            if (result.isLeft()) {
                // Keep creation atomic: roll back the project and any milestones already persisted.
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                }
                return ProjectView.error(result.getLeft());
            }
        }
        return toView(projectEntity);
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
        if (allocationRepository.existsByMilestoneProjectIdAndEventStatus(projectId, EventStatus.PUBLISHED)) {
            return Optional.of(Problems.conflict(
                    "Cannot delete project linked to a published event: %s".formatted(projectId),
                    ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED));
        }
        projectRepository.deleteById(projectId);
        return Optional.empty();
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

    public ProjectView toView(ProjectEntity project) {
        List<MilestoneView> milestoneViews = milestoneService.findByProjectId(project.getId()).stream()
                .map(milestoneService::toView)
                .toList();

        List<ProjectView> subProjectViews = projectRepository.findByParentProjectId(project.getId()).stream()
                .map(this::toView)
                .toList();

        String parentProjectId = project.getParentProject() != null ? project.getParentProject().getId() : null;

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
                .build();
    }

    private ProjectEntity toEntity(ProjectWithMilestonesCreateRequest request) {
        return ProjectEntity.builder()
                .id(ProjectEntity.id(request.getOrganisationId(), request.getExternalProjectId()))
                .organisationId(request.getOrganisationId())
                .fundingId(request.getFundingId())
                .externalProjectId(request.getExternalProjectId())
                .projectTitle(request.getProjectTitle())
                .totalAmount(request.getTotalAmount())
                .currency(request.getCurrency())
                .build();
    }

    private static ProblemDetail projectNotFound(String projectId) {
        return Problems.notFound(PROJECT_NOT_FOUND_DETAIL + projectId, ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

}
