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
import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {

    private final FundingProjectRepository projectRepository;
    private final MilestoneService milestoneService;

    public Optional<ProjectEntity> findById(String projectUid) {
        return projectRepository.findById(projectUid);
    }

    public List<ProjectEntity> findByOrganisationId(String organisationId) {
        return projectRepository.findByOrganisationId(organisationId);
    }

    public Page<ProjectEntity> findByOrganisationId(String organisationId, Pageable pageable) {
        return projectRepository.findByOrganisationId(organisationId, pageable);
    }

    public boolean existsByOrganisationIdAndProjectId(String organisationId, String projectId) {
        return projectRepository.existsByOrganisationIdAndProjectId(organisationId, projectId);
    }

    @Transactional
    public ProjectEntity createWithMilestones(ProjectWithMilestonesCreateRequest request) {
        String projectUid = ProjectEntity.id(request.getOrganisationId(), request.getProjectId());
        projectRepository.saveAndFlush(toEntity(projectUid, request));
        request.getMilestones().forEach(milestoneRequest -> milestoneService.create(projectUid, milestoneRequest));
        return projectRepository.findById(projectUid).orElseThrow();
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

        String parentProjectUid = project.getParentProject() != null ? project.getParentProject().getId() : null;

        return ProjectView.builder()
                .projectUid(project.getId())
                .organisationId(project.getOrganisationId())
                .fundingId(project.getFundingId())
                .projectId(project.getProjectId())
                .projectTitle(project.getProjectTitle())
                .totalAmount(project.getTotalAmount())
                .currency(project.getCurrency())
                .parentProjectUid(parentProjectUid)
                .createdAt(project.getCreatedAt())
                .milestones(milestoneViews)
                .subProjects(subProjectViews)
                .build();
    }

    private ProjectEntity toEntity(String projectUid, ProjectWithMilestonesCreateRequest request) {
        return ProjectEntity.builder()
                .id(projectUid)
                .organisationId(request.getOrganisationId())
                .fundingId(request.getFundingId())
                .projectId(request.getProjectId())
                .projectTitle(request.getProjectTitle())
                .totalAmount(request.getTotalAmount())
                .currency(request.getCurrency())
                .build();
    }

}
