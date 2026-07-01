package org.cardanofoundation.lob.app.funding.service;

import java.math.BigDecimal;
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
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.funding.util.FundingValidations;
import org.cardanofoundation.lob.app.funding.util.Problems;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MilestoneService {

    private static final String PROJECT_NOT_FOUND_DETAIL = "Project not found: ";

    private final MilestoneRepository milestoneRepository;
    private final FundingProjectRepository projectRepository;
    private final EventMilestoneAllocationRepository allocationRepository;
    private final KeycloakSecurityHelper keycloakSecurityHelper;

    // -------------------------------------------------------------------------
    // View-returning API (used by the controller — carries the ProblemDetail).
    // A milestone's organisation is its project's, so access is scoped to the
    // path project and the milestone is confirmed to belong to it.
    // -------------------------------------------------------------------------

    public PagedResponse<MilestoneView> listMilestones(String projectId, Pageable pageable) {
        Optional<ProblemDetail> denied = authorizeProject(projectId);
        if (denied.isPresent()) {
            return PagedResponse.error(denied.get());
        }
        return PagedResponse.of(milestoneRepository.findByProjectId(projectId, pageable), this::toView);
    }

    public MilestoneView getMilestone(String projectId, String milestoneId) {
        Optional<ProblemDetail> denied = authorizeProject(projectId);
        if (denied.isPresent()) {
            return MilestoneView.error(denied.get());
        }
        return milestoneRepository.findByIdAndProjectId(milestoneId, projectId)
                .map(this::toView)
                .orElseGet(() -> MilestoneView.error(milestoneNotFound(milestoneId)));
    }

    @Transactional
    public MilestoneView createMilestone(String projectId, MilestoneCreateRequest request) {
        Optional<ProblemDetail> denied = authorizeProject(projectId);
        if (denied.isPresent()) {
            return MilestoneView.error(denied.get());
        }
        return create(projectId, request).fold(MilestoneView::error, this::toView);
    }

    @Transactional
    public MilestoneView updateMilestone(String projectId, String milestoneId, MilestoneUpdateRequest request) {
        Optional<ProblemDetail> denied = authorizeProject(projectId);
        if (denied.isPresent()) {
            return MilestoneView.error(denied.get());
        }
        if (milestoneRepository.findByIdAndProjectId(milestoneId, projectId).isEmpty()) {
            return MilestoneView.error(milestoneNotFound(milestoneId));
        }
        return update(milestoneId, request).fold(MilestoneView::error, this::toView);
    }

    @Transactional
    public Optional<ProblemDetail> deleteMilestone(String projectId, String milestoneId) {
        Optional<ProblemDetail> denied = authorizeProject(projectId);
        if (denied.isPresent()) {
            return denied;
        }
        if (milestoneRepository.findByIdAndProjectId(milestoneId, projectId).isEmpty()) {
            return Optional.of(milestoneNotFound(milestoneId));
        }
        return delete(milestoneId).fold(Optional::of, ignored -> Optional.empty());
    }

    private Optional<ProblemDetail> authorizeProject(String projectId) {
        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);
        if (projectM.isEmpty()) {
            return Optional.of(Problems.notFound(PROJECT_NOT_FOUND_DETAIL + projectId, ErrorTitleConstants.PROJECT_NOT_FOUND));
        }
        if (!keycloakSecurityHelper.canUserAccessOrg(projectM.get().getOrganisationId())) {
            return Optional.of(Problems.unauthorized());
        }
        return Optional.empty();
    }

    private static ProblemDetail milestoneNotFound(String milestoneId) {
        return Problems.notFound("Milestone not found: " + milestoneId, ErrorTitleConstants.MILESTONE_NOT_FOUND);
    }

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

    /** Whether the project has at least one milestone — used to enforce the milestones-XOR-subprojects rule. */
    public boolean hasMilestones(String projectId) {
        return milestoneRepository.existsByProjectId(projectId);
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
        ProjectEntity project = projectM.orElseThrow();

        Optional<ProblemDetail> structure = FundingValidations.milestoneAllowed(projectRepository.existsByParentProjectId(projectId));
        if (structure.isPresent()) {
            return Either.left(structure.get());
        }

        MilestoneEntity entity = toEntity(request, project);
        Optional<MilestoneEntity> milestoneExists = milestoneRepository.findById(entity.getId());
        if(milestoneExists.isPresent()) {
            log.warn("Milestone already exists for id: {}", entity.getId());
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Milestone already exists for id: %s".formatted(entity.getId()));
            problemDetail.setTitle("MILESTONE_ALREADY_EXISTS");
            return Either.left(problemDetail);
        }

        BigDecimal otherMilestonesTotal = FundingValidations.sumMilestoneAmounts(
                milestoneRepository.findByProjectId(projectId), null);
        Optional<ProblemDetail> validation = FundingValidations.milestone(
                request.getMilestoneAmount(), request.getMilestoneDate(), project, otherMilestonesTotal);
        if (validation.isPresent()) {
            return Either.left(validation.get());
        }
        return Either.right(milestoneRepository.saveAndFlush(entity));
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

        // Validate only the supplied fields against the milestone's project; cumulative budget
        // excludes this milestone's current amount so an unchanged amount can't trip the check.
        ProjectEntity project = milestone.getProject();
        BigDecimal otherMilestonesTotal = FundingValidations.sumMilestoneAmounts(
                milestoneRepository.findByProjectId(project.getId()), milestoneId);
        Optional<ProblemDetail> validation = FundingValidations.milestone(
                request.getMilestoneAmount(), request.getMilestoneDate(), project, otherMilestonesTotal);
        if (validation.isPresent()) {
            return Either.left(validation.get());
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
