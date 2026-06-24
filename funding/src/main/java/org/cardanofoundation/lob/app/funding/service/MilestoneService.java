package org.cardanofoundation.lob.app.funding.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MilestoneService {

    private final MilestoneRepository milestoneRepository;
    private final FundingProjectRepository projectRepository;

    public Optional<MilestoneEntity> findById(String milestoneId) {
        return milestoneRepository.findById(milestoneId);
    }

    public List<MilestoneEntity> findByProjectId(String projectId) {
        return milestoneRepository.findByProject_Id(projectId);
    }

    public Page<MilestoneEntity> findByProjectId(String projectId, Pageable pageable) {
        return milestoneRepository.findByProject_Id(projectId, pageable);
    }

    @Transactional
    public Either<ProblemDetail, MilestoneEntity> create(String projectId, MilestoneCreateRequest request) {
        if (request.getLabel() == null || request.getExpectedCost() == null
                || request.getCurrency() == null || request.getDueDate() == null) {
            log.warn("Missing required fields for milestone creation in project: {}", projectId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "label, expectedCost, currency, dueDate are required when creating a new milestone");
            problem.setTitle("MILESTONE_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);

        if (projectM.isEmpty()) {
            log.warn("Project not found for id: {}", projectId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Project not found for id: %s".formatted(projectId));
            problem.setTitle("PROJECT_NOT_FOUND");
            return Either.left(problem);
        }

        return Either.right(milestoneRepository.saveAndFlush(toEntity(request, projectM.orElseThrow())));
    }

    @Transactional
    public Either<ProblemDetail, MilestoneEntity> update(String milestoneId, MilestoneUpdateRequest request) {
        Optional<MilestoneEntity> milestoneM = milestoneRepository.findById(milestoneId);

        if (milestoneM.isEmpty()) {
            log.warn("Milestone not found for id: {}", milestoneId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Milestone not found for id: %s".formatted(milestoneId));
            problem.setTitle("MILESTONE_NOT_FOUND");
            return Either.left(problem);
        }

        MilestoneEntity milestone = milestoneM.orElseThrow();
        if (request.getLabel() != null) {
            milestone.setLabel(request.getLabel());
        }
        if (request.getExpectedCost() != null) {
            milestone.setExpectedCost(request.getExpectedCost());
        }
        if (request.getCurrency() != null) {
            milestone.setCurrency(request.getCurrency());
        }
        if (request.getDueDate() != null) {
            milestone.setDueDate(request.getDueDate());
        }

        return Either.right(milestoneRepository.saveAndFlush(milestone));
    }

    @Transactional
    public Either<ProblemDetail, Void> delete(String milestoneId) {
        if (!milestoneRepository.existsById(milestoneId)) {
            log.warn("Milestone not found for id: {}", milestoneId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Milestone not found for id: %s".formatted(milestoneId));
            problem.setTitle("MILESTONE_NOT_FOUND");
            return Either.left(problem);
        }
        milestoneRepository.deleteById(milestoneId);
        return Either.right(null);
    }

    public boolean belongsToProject(MilestoneEntity milestone, ProjectEntity project) {
        return milestone.getProject().getId().equals(project.getId());
    }

    public MilestoneView toView(MilestoneEntity milestone) {
        return MilestoneView.builder()
                .milestoneId(milestone.getId())
                .projectId(milestone.getProject().getId())
                .label(milestone.getLabel())
                .expectedCost(milestone.getExpectedCost())
                .currency(milestone.getCurrency())
                .dueDate(milestone.getDueDate())
                .build();
    }

    private MilestoneEntity toEntity(MilestoneCreateRequest request, ProjectEntity project) {
        return MilestoneEntity.builder()
                .id(UUID.randomUUID().toString())
                .label(request.getLabel())
                .expectedCost(request.getExpectedCost())
                .currency(request.getCurrency())
                .dueDate(request.getDueDate())
                .project(project)
                .build();
    }

}
