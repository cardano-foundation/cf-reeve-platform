package org.cardanofoundation.lob.app.funding.service;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {

    private final FundingProjectRepository projectRepository;
    private final MilestoneService milestoneService;
    private final EventMilestoneAllocationRepository allocationRepository;

    public Optional<ProjectEntity> findById(String projectUid) {
        return projectRepository.findById(projectUid);
    }

    public List<ProjectEntity> findByOrganisationId(String organisationId) {
        return projectRepository.findByOrganisationId(organisationId);
    }

    public Page<ProjectEntity> findByOrganisationId(String organisationId, Pageable pageable) {
        return projectRepository.findByOrganisationId(organisationId, pageable);
    }

    public boolean existsByOrganisationIdAndExternalProjectId(String organisationId, String externalProjectId) {
        return projectRepository.existsByOrganisationIdAndExternalProjectId(organisationId, externalProjectId);
    }

    @Transactional
    public ProjectEntity createWithMilestones(ProjectWithMilestonesCreateRequest request) {
        String projectId = ProjectEntity.id(request.getOrganisationId(), request.getExternalProjectId());
        projectRepository.saveAndFlush(toEntity(projectId, request));
        request.getMilestones().forEach(milestoneRequest -> milestoneService.create(projectId, milestoneRequest));
        return projectRepository.findById(projectId).orElseThrow();
    }

    @Transactional
    public Either<ProblemDetail, ProjectEntity> update(String projectUid, ProjectUpdateRequest request) {
        Optional<ProjectEntity> projectM = projectRepository.findById(projectUid);
        if (projectM.isEmpty()) {
            log.warn("Project not found: {}", projectUid);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Project not found: %s".formatted(projectUid));
            problem.setTitle("PROJECT_NOT_FOUND");
            return Either.left(problem);
        }

        ProjectEntity project = projectM.get();

        if (allocationRepository.existsByMilestone_Project_IdAndEvent_Status(projectUid, EventStatus.PUBLISHED)) {
            log.warn("Cannot update project linked to a published event: {}", projectUid);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "Cannot update project linked to a published event: %s".formatted(projectUid));
            problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
            return Either.left(problem);
        }

        if (request.getProjectTitle() != null) project.setProjectTitle(request.getProjectTitle());
        if (request.getTotalAmount() != null) project.setTotalAmount(request.getTotalAmount());
        if (request.getCurrency() != null) project.setCurrency(request.getCurrency());

        return Either.right(projectRepository.saveAndFlush(project));
    }

    @Transactional
    public Either<ProblemDetail, Void> delete(String projectUid) {
        if (!projectRepository.existsById(projectUid)) {
            log.warn("Project not found: {}", projectUid);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Project not found: %s".formatted(projectUid));
            problem.setTitle("PROJECT_NOT_FOUND");
            return Either.left(problem);
        }
        if (allocationRepository.existsByMilestone_Project_IdAndEvent_Status(projectUid, EventStatus.PUBLISHED)) {
            log.warn("Cannot delete project linked to a published event: {}", projectUid);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "Cannot delete project linked to a published event: %s".formatted(projectUid));
            problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
            return Either.left(problem);
        }
        projectRepository.deleteById(projectUid);
        return Either.right(null);
    }

    public ProjectView toView(ProjectEntity project) {
        List<MilestoneView> milestoneViews = milestoneService.findByProjectId(project.getId()).stream()
                .map(milestoneService::toView)
                .toList();

        List<ProjectView> subProjectViews = projectRepository.findByParentProject_Id(project.getId()).stream()
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

    private ProjectEntity toEntity(String projectId, ProjectWithMilestonesCreateRequest request) {
        return ProjectEntity.builder()
                .id(projectId)
                .organisationId(request.getOrganisationId())
                .fundingId(request.getFundingId())
                .externalProjectId(request.getExternalProjectId())
                .projectTitle(request.getProjectTitle())
                .totalAmount(request.getTotalAmount())
                .currency(request.getCurrency())
                .build();
    }

}
