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

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MilestoneService {

    private final MilestoneRepository milestoneRepository;
    private final FundingProjectRepository projectRepository;
    private final EventMilestoneAllocationRepository allocationRepository;

    public Optional<MilestoneEntity> findById(String milestoneId) {
        return milestoneRepository.findById(milestoneId);
    }

    /** Returns the milestone only when it belongs to the given project (for ownership-scoped access). */
    public Optional<MilestoneEntity> findByIdAndProjectId(String milestoneId, String projectId) {
        return milestoneRepository.findByIdAndProjectId(milestoneId, projectId);
    }

    public List<MilestoneEntity> findByProjectId(String projectId) {
        return milestoneRepository.findByProjectId(projectId);
    }

    public Page<MilestoneEntity> findByProjectId(String projectId, Pageable pageable) {
        return milestoneRepository.findByProjectId(projectId, pageable);
    }

    @Transactional
    public Either<ProblemDetail, MilestoneEntity> create(String projectId, MilestoneCreateRequest request) {
        if (request.getMilestoneTitle() == null || request.getMilestoneAmount() == null
                || request.getCurrency() == null || request.getMilestoneDate() == null) {
            log.warn("Missing required fields for milestone creation in project: {}", projectId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "milestoneTitle, milestoneAmount, currency, milestoneDate are required when creating a new milestone");
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

        if (allocationRepository.existsByMilestoneIdAndEventStatus(milestoneId, EventStatus.PUBLISHED)) {
            log.warn("Cannot update milestone linked to a published event: {}", milestoneId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "Cannot update milestone linked to a published event: %s".formatted(milestoneId));
            problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
            return Either.left(problem);
        }

        if (request.getMilestoneTitle() != null) {
            milestone.setMilestoneTitle(request.getMilestoneTitle());
        }
        if (request.getMilestoneAmount() != null) {
            milestone.setMilestoneAmount(request.getMilestoneAmount());
        }
        if (request.getCurrency() != null) {
            milestone.setCurrency(request.getCurrency());
        }
        if (request.getMilestoneDate() != null) {
            milestone.setMilestoneDate(request.getMilestoneDate());
        }

        return Either.right(milestoneRepository.saveAndFlush(milestone));
    }

    @Transactional
    public Either<ProblemDetail, Void> delete(String mileStoneId) {
        if (!milestoneRepository.existsById(mileStoneId)) {
            log.warn("Milestone not found for id: {}", mileStoneId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Milestone not found for id: %s".formatted(mileStoneId));
            problem.setTitle("MILESTONE_NOT_FOUND");
            return Either.left(problem);
        }
        if (allocationRepository.existsByMilestoneIdAndEventStatus(mileStoneId, EventStatus.PUBLISHED)) {
            log.warn("Cannot delete milestone linked to a published event: {}", mileStoneId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "Cannot delete milestone linked to a published event: %s".formatted(mileStoneId));
            problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
            return Either.left(problem);
        }
        milestoneRepository.deleteById(mileStoneId);
        return Either.right(null);
    }

    public boolean belongsToProject(MilestoneEntity milestone, ProjectEntity project) {
        return milestone.getProject().getId().equals(project.getId());
    }

    public MilestoneView toView(MilestoneEntity milestone) {
        return MilestoneView.builder()
                .milestoneId(milestone.getId())
                .externalMilestoneId(milestone.getExternalMilestoneId())
                .projectId(milestone.getProject().getId())
                .milestoneTitle(milestone.getMilestoneTitle())
                .milestoneAmount(milestone.getMilestoneAmount())
                .currency(milestone.getCurrency())
                .milestoneDate(milestone.getMilestoneDate())
                .build();
    }

    private MilestoneEntity toEntity(MilestoneCreateRequest request, ProjectEntity project) {
        String id = request.getExternalMilestoneId() != null
                ? MilestoneEntity.id(project.getId(), request.getExternalMilestoneId())
                : MilestoneEntity.contentId(project.getId(), request.getMilestoneTitle(),
                        request.getMilestoneAmount(), request.getCurrency(), request.getMilestoneDate());
        return MilestoneEntity.builder()
                .id(id)
                .externalMilestoneId(request.getExternalMilestoneId())
                .milestoneTitle(request.getMilestoneTitle())
                .milestoneAmount(request.getMilestoneAmount())
                .currency(request.getCurrency())
                .milestoneDate(request.getMilestoneDate())
                .project(project)
                .build();
    }

}
