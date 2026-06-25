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

    public Optional<MilestoneEntity> findById(String milestoneUid) {
        return milestoneRepository.findById(milestoneUid);
    }

    public List<MilestoneEntity> findByProjectId(String projectUid) {
        return milestoneRepository.findByProject_Id(projectUid);
    }

    public Page<MilestoneEntity> findByProjectId(String projectUid, Pageable pageable) {
        return milestoneRepository.findByProject_Id(projectUid, pageable);
    }

    @Transactional
    public Either<ProblemDetail, MilestoneEntity> create(String projectUid, MilestoneCreateRequest request) {
        if (request.getMilestoneTitle() == null || request.getMilestoneAmount() == null
                || request.getCurrency() == null || request.getMilestoneDate() == null) {
            log.warn("Missing required fields for milestone creation in project: {}", projectUid);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "milestoneTitle, milestoneAmount, currency, milestoneDate are required when creating a new milestone");
            problem.setTitle("MILESTONE_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        Optional<ProjectEntity> projectM = projectRepository.findById(projectUid);

        if (projectM.isEmpty()) {
            log.warn("Project not found for id: {}", projectUid);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Project not found for id: %s".formatted(projectUid));
            problem.setTitle("PROJECT_NOT_FOUND");
            return Either.left(problem);
        }

        return Either.right(milestoneRepository.saveAndFlush(toEntity(request, projectM.orElseThrow())));
    }

    @Transactional
    public Either<ProblemDetail, MilestoneEntity> update(String milestoneUid, MilestoneUpdateRequest request) {
        Optional<MilestoneEntity> milestoneM = milestoneRepository.findById(milestoneUid);

        if (milestoneM.isEmpty()) {
            log.warn("Milestone not found for id: {}", milestoneUid);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Milestone not found for id: %s".formatted(milestoneUid));
            problem.setTitle("MILESTONE_NOT_FOUND");
            return Either.left(problem);
        }

        MilestoneEntity milestone = milestoneM.orElseThrow();
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
    public Either<ProblemDetail, Void> delete(String milestoneUid) {
        if (!milestoneRepository.existsById(milestoneUid)) {
            log.warn("Milestone not found for id: {}", milestoneUid);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Milestone not found for id: %s".formatted(milestoneUid));
            problem.setTitle("MILESTONE_NOT_FOUND");
            return Either.left(problem);
        }
        milestoneRepository.deleteById(milestoneUid);
        return Either.right(null);
    }

    public boolean belongsToProject(MilestoneEntity milestone, ProjectEntity project) {
        return milestone.getProject().getId().equals(project.getId());
    }

    public MilestoneView toView(MilestoneEntity milestone) {
        return MilestoneView.builder()
                .milestoneUid(milestone.getId())
                .milestoneId(milestone.getMilestoneId())
                .projectUid(milestone.getProject().getId())
                .milestoneTitle(milestone.getMilestoneTitle())
                .milestoneAmount(milestone.getMilestoneAmount())
                .currency(milestone.getCurrency())
                .milestoneDate(milestone.getMilestoneDate())
                .build();
    }

    private MilestoneEntity toEntity(MilestoneCreateRequest request, ProjectEntity project) {
        return MilestoneEntity.builder()
                .id(UUID.randomUUID().toString())
                .milestoneId(request.getMilestoneId())
                .milestoneTitle(request.getMilestoneTitle())
                .milestoneAmount(request.getMilestoneAmount())
                .currency(request.getCurrency())
                .milestoneDate(request.getMilestoneDate())
                .project(project)
                .build();
    }

}
