package org.cardanofoundation.lob.app.funding.service;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {

    private final FundingProjectRepository projectRepository;
    private final MilestoneService milestoneService;
    private final SpendingEventService spendingEventService;

    public Optional<ProjectEntity> findById(String projectId) {
        return projectRepository.findById(projectId);
    }

    public List<ProjectEntity> findByOrganisationId(String organisationId) {
        return projectRepository.findByOrganisationId(organisationId);
    }

    public Page<ProjectEntity> findByOrganisationId(String organisationId, Pageable pageable) {
        return projectRepository.findByOrganisationId(organisationId, pageable);
    }

    public boolean existsByOrganisationIdAndActivityId(String organisationId, String activityId) {
        return projectRepository.existsByOrganisationIdAndActivityId(organisationId, activityId);
    }

    @Transactional
    public ProjectEntity create(ProjectCreateRequest request) {
        String projectId = ProjectEntity.id(request.getOrganisationId(), request.getActivityId());

        ProjectEntity project = ProjectEntity.builder()
                .id(projectId)
                .organisationId(request.getOrganisationId())
                .fundingId(request.getFundingId())
                .activityId(request.getActivityId())
                .activityTitle(request.getActivityTitle())
                .expectedTotalAmount(request.getExpectedTotalAmount())
                .currency(request.getCurrency())
                .build();

        return projectRepository.saveAndFlush(project);
    }

    @Transactional
    public ProjectEntity createWithMilestones(ProjectWithMilestonesCreateRequest request) {
        String projectId = ProjectEntity.id(request.getOrganisationId(), request.getActivityId());

        ProjectEntity project = ProjectEntity.builder()
                .id(projectId)
                .organisationId(request.getOrganisationId())
                .fundingId(request.getFundingId())
                .activityId(request.getActivityId())
                .activityTitle(request.getActivityTitle())
                .expectedTotalAmount(request.getExpectedTotalAmount())
                .currency(request.getCurrency())
                .build();
        projectRepository.saveAndFlush(project);

        request.getMilestones().forEach(milestoneRequest ->
                milestoneService.create(projectId, milestoneRequest));

        return projectRepository.findById(projectId).orElseThrow();
    }

    @Transactional
    public Optional<ProjectEntity> update(String projectId, ProjectUpdateRequest request) {
        return projectRepository.findById(projectId).map(project -> {
            if (request.getActivityTitle() != null) {
                project.setActivityTitle(request.getActivityTitle());
            }
            if (request.getExpectedTotalAmount() != null) {
                project.setExpectedTotalAmount(request.getExpectedTotalAmount());
            }
            if (request.getCurrency() != null) {
                project.setCurrency(request.getCurrency());
            }
            return projectRepository.saveAndFlush(project);
        });
    }

    @Transactional
    public boolean delete(String projectId) {
        if (!projectRepository.existsById(projectId)) {
            return false;
        }
        projectRepository.deleteById(projectId);
        return true;
    }

    public ProjectView toView(ProjectEntity project) {
        List<MilestoneView> milestoneViews = milestoneService.findByProjectId(project.getId()).stream()
                .map(milestoneService::toView)
                .toList();

        List<SpendingEventView> eventViews = spendingEventService.findByProjectId(project.getId()).stream()
                .map(spendingEventService::toView)
                .toList();

        return ProjectView.builder()
                .projectId(project.getId())
                .organisationId(project.getOrganisationId())
                .fundingId(project.getFundingId())
                .activityId(project.getActivityId())
                .activityTitle(project.getActivityTitle())
                .expectedTotalAmount(project.getExpectedTotalAmount())
                .currency(project.getCurrency())
                .createdAt(project.getCreatedAt())
                .milestones(milestoneViews)
                .events(eventViews)
                .build();
    }

}
