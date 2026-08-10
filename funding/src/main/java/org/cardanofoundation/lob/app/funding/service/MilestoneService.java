package org.cardanofoundation.lob.app.funding.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
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

    private final MilestoneRepository milestoneRepository;
    private final FundingProjectRepository projectRepository;
    private final EventMilestoneAllocationRepository allocationRepository;
    private final KeycloakSecurityHelper keycloakSecurityHelper;
    private final FundingCascadeDeleteService cascadeDeleteService;

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
                .orElseGet(() -> MilestoneView.error(Problems.milestoneNotFound(milestoneId)));
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
            return MilestoneView.error(Problems.milestoneNotFound(milestoneId));
        }
        return update(milestoneId, request).fold(MilestoneView::error, this::toView);
    }

    @Transactional
    public Optional<ProblemDetail> deleteMilestone(String projectId, String milestoneId) {
        Optional<ProblemDetail> denied = authorizeProject(projectId);
        if (denied.isPresent()) {
            return denied;
        }
        Optional<MilestoneEntity> milestoneM = milestoneRepository.findByIdAndProjectId(milestoneId, projectId);
        if (milestoneM.isEmpty()) {
            return Optional.of(Problems.milestoneNotFound(milestoneId));
        }
        // Cascade: fails when the milestone is linked to a published event; otherwise the referencing
        // draft-event allocations are cleaned up and the milestone is removed.
        return cascadeDeleteService.deleteMilestone(milestoneM.get());
    }

    private Optional<ProblemDetail> authorizeProject(String projectId) {
        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);
        if (projectM.isEmpty()) {
            return Optional.of(Problems.projectNotFound(projectId));
        }
        if (!keycloakSecurityHelper.canUserAccessOrg(projectM.get().getOrganisationId())) {
            return Optional.of(Problems.unauthorized());
        }
        return Optional.empty();
    }

    public Optional<MilestoneEntity> findById(String milestoneId) {
        return milestoneRepository.findById(milestoneId);
    }

    /** Returns the milestone only when it belongs to the given project (for ownership-scoped access). */
    public Optional<MilestoneEntity> findByIdAndProjectId(String milestoneId, String projectId) {
        return milestoneRepository.findByIdAndProjectId(milestoneId, projectId);
    }

    /** Looks up a milestone by its user-defined external id within a project — used by the bulk CSV importer's upsert logic. */
    public Optional<MilestoneEntity> findByProjectIdAndExternalMilestoneId(String projectId, String externalMilestoneId) {
        return milestoneRepository.findByProjectIdAndExternalMilestoneId(projectId, externalMilestoneId);
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
        if (missingCreationFields(request)) {
            log.warn("Missing required fields for milestone creation in project: {}", projectId);
            return Either.left(milestoneFieldsRequired());
        }

        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);
        if (projectM.isEmpty()) {
            log.warn("Project not found for id: {}", projectId);
            return Either.left(Problems.projectNotFound(projectId));
        }
        ProjectEntity project = projectM.orElseThrow();

        return validateAndSave(project, toEntity(request, project), request);
    }

    /**
     * Resolves the milestone referenced by the request within {@code project}, or creates it when
     * the creation fields are supplied — the shared semantics of the event-allocation flow. A request
     * carrying only an id (no creation fields) references an existing milestone and fails as
     * not-found when none exists; a matching already-existing milestone is returned as is.
     */
    @Transactional
    public Either<ProblemDetail, MilestoneEntity> resolveOrCreate(ProjectEntity project, MilestoneCreateRequest request) {
        if (request.getMilestoneTitle() == null) {
            return Either.left(milestoneFieldsRequired());
        }

        Optional<MilestoneEntity> existing = milestoneRepository.findById(
                MilestoneEntity.id(project.getId(), request.getMilestoneTitle()));
        if (existing.isPresent()) {
            return Either.right(existing.get());
        }

        if (missingCreationFields(request)) {
            // Title supplied but no milestone exists and creation fields are incomplete → referencing a
            // milestone that does not exist.
            log.warn("Milestone not found: {} in project: {}", request.getMilestoneTitle(), project.getId());
            return Either.left(Problems.milestoneNotFound(request.getMilestoneTitle()));
        }

        return validateAndSave(project, toEntity(request, project), request);
    }

    /** Shared creation core: structure rule, budget validations, persist. */
    private Either<ProblemDetail, MilestoneEntity> validateAndSave(ProjectEntity project,
            MilestoneEntity entity, MilestoneCreateRequest request) {
        Optional<ProblemDetail> structure = FundingValidations.milestoneAllowed(
                projectRepository.existsByParentProjectId(project.getId()));
        if (structure.isPresent()) {
            return Either.left(structure.get());
        }
        if (milestoneRepository.existsByProjectIdAndMilestoneTitle(project.getId(), entity.getMilestoneTitle())) {
            return Either.left(Problems.conflict(
                    "Milestone title already exists in this project: " + entity.getMilestoneTitle(),
                    ErrorTitleConstants.MILESTONE_TITLE_ALREADY_EXISTS));
        }
        BigDecimal otherMilestonesTotal = FundingValidations.sumMilestoneAmounts(
                milestoneRepository.findByProjectId(project.getId()), null);
        Optional<ProblemDetail> validation = FundingValidations.milestone(
                request.getMilestoneAmount(), project, otherMilestonesTotal);
        if (validation.isPresent()) {
            return Either.left(validation.get());
        }
        return Either.right(milestoneRepository.saveAndFlush(entity));
    }

    private static boolean missingCreationFields(MilestoneCreateRequest request) {
        return request.getMilestoneTitle() == null || request.getMilestoneAmount() == null
                || request.getCurrency() == null || request.getMilestoneDate() == null;
    }

    private static ProblemDetail milestoneFieldsRequired() {
        return Problems.badRequest(
                "milestoneTitle, milestoneAmount, currency, milestoneDate are required when creating a new milestone",
                ErrorTitleConstants.MILESTONE_FIELDS_REQUIRED);
    }

    @Transactional
    public Either<ProblemDetail, MilestoneEntity> update(String milestoneId, MilestoneUpdateRequest request) {
        Optional<MilestoneEntity> milestoneM = milestoneRepository.findById(milestoneId);

        if (milestoneM.isEmpty()) {
            log.warn("Milestone not found for id: {}", milestoneId);
            return Either.left(Problems.milestoneNotFound(milestoneId));
        }

        MilestoneEntity milestone = milestoneM.orElseThrow();

        if (allocationRepository.existsByMilestoneIdAndEventStatus(milestoneId, EventStatus.PUBLISHED)) {
            log.warn("Cannot update milestone linked to a published event: {}", milestoneId);
            return Either.left(Problems.conflict(
                    "Cannot update milestone linked to a published event: %s".formatted(milestoneId),
                    ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED));
        }

        // Validate only the supplied fields against the milestone's project; cumulative budget
        // excludes this milestone's current amount so an unchanged amount can't trip the check.
        ProjectEntity project = milestone.getProject();

        // milestoneTitle is immutable — the milestone's id is derived from it, so changing it would
        // leave the id stale relative to its new title.
        if (request.getMilestoneTitle() != null && !request.getMilestoneTitle().equals(milestone.getMilestoneTitle())) {
            log.warn("Attempted to change immutable milestoneTitle for milestone: {}", milestoneId);
            return Either.left(Problems.badRequest(
                    "milestoneTitle cannot be changed on update (id is derived from it)",
                    ErrorTitleConstants.MILESTONE_TITLE_IMMUTABLE));
        }
        BigDecimal otherMilestonesTotal = FundingValidations.sumMilestoneAmounts(
                milestoneRepository.findByProjectId(project.getId()), milestoneId);
        Optional<ProblemDetail> validation = FundingValidations.milestone(
                request.getMilestoneAmount(), project, otherMilestonesTotal);
        if (validation.isPresent()) {
            return Either.left(validation.get());
        }

        if (request.getMilestoneAmount() != null) {
            Optional<ProblemDetail> coverage = FundingValidations.milestoneCoversAllocations(
                    request.getMilestoneAmount(), allocationRepository.sumAllocatedByMilestoneId(milestoneId));
            if (coverage.isPresent()) {
                return Either.left(coverage.get());
            }
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
                .spentAmount(allocationRepository.spentAmountByMilestoneId(
                        milestone.getId(), EventType.SPENDING, EventType.REFUND))
                .build();
    }

    private MilestoneEntity toEntity(MilestoneCreateRequest request, ProjectEntity project) {
        return MilestoneEntity.builder()
                .id(MilestoneEntity.id(project.getId(), request.getMilestoneTitle()))
                .milestoneTitle(request.getMilestoneTitle())
                .milestoneAmount(request.getMilestoneAmount())
                .currency(request.getCurrency())
                .milestoneDate(request.getMilestoneDate())
                .project(project)
                .build();
    }

}
