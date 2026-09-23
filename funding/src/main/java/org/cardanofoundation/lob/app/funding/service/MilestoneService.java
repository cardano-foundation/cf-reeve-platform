package org.cardanofoundation.lob.app.funding.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import jakarta.annotation.Nullable;

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
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Currency;
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
    private final OrganisationPublicApiIF organisationPublicApi;
    private final ProjectChildSequenceService childSequenceService;

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
        return createMilestoneInternal(projectId, request, null);
    }

    /** CSV-bulk-import-only: see {@link #create(String, MilestoneCreateRequest, String)}'s Javadoc. */
    @Transactional
    public MilestoneView createMilestone(String projectId, MilestoneCreateRequest request, @Nullable String explicitProId) {
        return createMilestoneInternal(projectId, request, explicitProId);
    }

    /**
     * Shared body for both {@code createMilestone} overloads above — calling {@link #createInternal}
     * directly (not the public, {@code @Transactional} {@link #create} methods) so neither overload
     * invokes another {@code @Transactional} method via {@code this}, which would silently bypass
     * Spring's proxy-based transaction management.
     */
    private MilestoneView createMilestoneInternal(String projectId, MilestoneCreateRequest request, @Nullable String explicitProId) {
        Optional<ProblemDetail> denied = authorizeProject(projectId);
        if (denied.isPresent()) {
            return MilestoneView.error(denied.get());
        }
        return createInternal(projectId, request, explicitProId).fold(MilestoneView::error, this::toView);
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

    /** Looks up a milestone by its title within a project — used by the bulk CSV importer's upsert logic. */
    public Optional<MilestoneEntity> findByProjectIdAndMilestoneTitle(String projectId, String milestoneTitle) {
        return milestoneRepository.findByProjectIdAndMilestoneTitle(projectId, milestoneTitle);
    }

    /** Looks up a milestone by its permanent proId within a project — preferred over title once a rename may have happened. See {@link MilestoneEntity#proId}. */
    public Optional<MilestoneEntity> findByProjectIdAndProId(String projectId, String proId) {
        return milestoneRepository.findByProjectIdAndProId(projectId, proId);
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

    /**
     * Sets every milestone of {@code projectId} to {@code currency} — a milestone's currency always
     * mirrors its owning project's, so this keeps them in sync when the project's currency changes
     * (see {@link ProjectService#cascadeCurrency}).
     */
    @Transactional
    void updateCurrencyForProject(String projectId, String currency) {
        List<MilestoneEntity> milestones = milestoneRepository.findByProjectId(projectId);
        milestones.forEach(m -> m.setCurrency(currency));
        milestoneRepository.saveAll(milestones);
    }

    /** Creates a milestone with an auto-assigned proId — the UI-facing JSON API entry point, which never supplies one. */
    @Transactional
    public Either<ProblemDetail, MilestoneEntity> create(String projectId, MilestoneCreateRequest request) {
        return createInternal(projectId, request, null);
    }

    /**
     * CSV-bulk-import-only entry point: {@code explicitProId} is used as the new milestone's proId as-is
     * (after a uniqueness check) instead of the usual system-assigned {@code project.proId + "-" + n}.
     * See {@link ProjectStructureService}'s matching overload for why CSV is the one caller allowed to
     * supply its own value.
     */
    @Transactional
    public Either<ProblemDetail, MilestoneEntity> create(String projectId, MilestoneCreateRequest request, @Nullable String explicitProId) {
        return createInternal(projectId, request, explicitProId);
    }

    /**
     * Shared body for both {@code create} overloads above (and for {@link #createMilestoneInternal}) —
     * a plain, non-{@code @Transactional} private method, so nothing here is ever reached via a
     * self-invoked {@code this.create(...)} call that would silently bypass Spring's proxy-based
     * transaction management; each public overload above carries its own {@code @Transactional}
     * instead, since each is independently called from outside this class.
     */
    private Either<ProblemDetail, MilestoneEntity> createInternal(String projectId, MilestoneCreateRequest request, @Nullable String explicitProId) {
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

        return validateAndSave(project, toEntity(request, project), request, explicitProId);
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

        // proId is permanent (see MilestoneEntity#proId) — when the caller supplies it, it's the
        // reliable way to find a milestone that may have since been renamed. Falling back to the
        // current title only resolves a milestone whose title still matches; recomputing the id hash
        // from the request's title (the old strategy) is deliberately not done here any more — see the
        // matching comment in SpendingEventService#resolveOrCreateRootProject for why.
        // A blank proId means "not supplied" — same as null — and falls back to title matching.
        Optional<MilestoneEntity> existing = (request.getProId() != null && !request.getProId().isBlank())
                ? milestoneRepository.findByProjectIdAndProId(project.getId(), request.getProId())
                : milestoneRepository.findByProjectIdAndMilestoneTitle(project.getId(), request.getMilestoneTitle());
        if (existing.isPresent()) {
            return Either.right(existing.get());
        }

        if (missingCreationFields(request)) {
            // Title supplied but no milestone exists and creation fields are incomplete → referencing a
            // milestone that does not exist.
            log.warn("Milestone not found: {} in project: {}", request.getMilestoneTitle(), project.getId());
            return Either.left(Problems.milestoneNotFound(request.getMilestoneTitle()));
        }

        // resolveOrCreate is the event-allocation flow — always auto-assigns proId on creation, same
        // as the plain create() JSON entry point; only the CSV-only overload of create() ever supplies
        // an explicit value.
        return validateAndSave(project, toEntity(request, project), request, null);
    }

    /** Shared creation core: structure rule, budget validations, persist. */
    private Either<ProblemDetail, MilestoneEntity> validateAndSave(ProjectEntity project,
            MilestoneEntity entity, MilestoneCreateRequest request, @Nullable String explicitProId) {
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
        Optional<ProblemDetail> currencyProblem = FundingValidations.currencyCode(
                request.getCurrency(), isCurrencyRegisteredAndActive(project.getOrganisationId(), request.getCurrency()));
        if (currencyProblem.isPresent()) {
            return Either.left(currencyProblem.get());
        }
        BigDecimal otherMilestonesTotal = FundingValidations.sumMilestoneAmounts(
                milestoneRepository.findByProjectId(project.getId()), null);
        Optional<ProblemDetail> validation = FundingValidations.milestone(
                request.getMilestoneAmount(), project, otherMilestonesTotal);
        if (validation.isPresent()) {
            return Either.left(validation.get());
        }
        // Assigned last, only once every other validation has passed — computing it earlier would burn
        // a sequence number (or reject a valid explicit value) on a request that ultimately fails
        // validation for an unrelated reason.
        if (explicitProId != null && !explicitProId.isBlank()) {
            // CSV path only — see the create() overload's Javadoc. Needs its own uniqueness pre-check
            // since, unlike the auto-assigned case, a caller-chosen value isn't guaranteed unique by
            // construction.
            if (milestoneRepository.existsByProjectIdAndProId(project.getId(), explicitProId)) {
                return Either.left(Problems.conflict(
                        "Milestone ID already exists in this project: " + explicitProId,
                        ErrorTitleConstants.MILESTONE_PROID_ALREADY_EXISTS));
            }
            entity.setProId(explicitProId);
        } else {
            entity.setProId(childSequenceService.nextChildProId(project));
        }
        // The primary key is derived from the proId (unique within the project), never from the
        // editable title — so it can only be set once the proId is known.
        entity.setId(MilestoneEntity.id(project.getId(), entity.getProId()));
        return Either.right(milestoneRepository.saveAndFlush(entity));
    }

    /**
     * Whether {@code currency} is registered and active in the organisation's currency table. Shared
     * by every funding service that validates a currency code (see {@link FundingValidations#currencyCode}),
     * since the org's table — not {@code java.util.Currency} — is the source of truth: it also covers
     * non-ISO-4217 codes such as crypto assets (e.g. {@code ADA}, registered under ISO 24165).
     */
    boolean isCurrencyRegisteredAndActive(String organisationId, String currency) {
        return organisationPublicApi.findCurrencyByCustomerCurrencyCode(organisationId, currency)
                .map(Currency::isActive)
                .orElse(false);
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

        // milestoneTitle is now a freely editable display attribute (see MilestoneEntity#proId, which
        // stays fixed and is what everything needing a stable reference uses instead) — still subject
        // to the same per-project uniqueness title always had.
        boolean titleChanging = request.getMilestoneTitle() != null && !request.getMilestoneTitle().equals(milestone.getMilestoneTitle());
        if (titleChanging && milestoneRepository.existsByProjectIdAndMilestoneTitleAndIdNot(
                project.getId(), request.getMilestoneTitle(), milestoneId)) {
            return Either.left(Problems.conflict(
                    "Milestone title already exists in this project: " + request.getMilestoneTitle(),
                    ErrorTitleConstants.MILESTONE_TITLE_ALREADY_EXISTS));
        }
        BigDecimal otherMilestonesTotal = FundingValidations.sumMilestoneAmounts(
                milestoneRepository.findByProjectId(project.getId()), milestoneId);
        Optional<ProblemDetail> validation = FundingValidations.milestone(
                request.getMilestoneAmount(), project, otherMilestonesTotal);
        if (validation.isPresent()) {
            return Either.left(validation.get());
        }
        Optional<ProblemDetail> currencyProblem = FundingValidations.currencyCode(
                request.getCurrency(), isCurrencyRegisteredAndActive(project.getOrganisationId(), request.getCurrency()));
        if (currencyProblem.isPresent()) {
            return Either.left(currencyProblem.get());
        }

        if (request.getMilestoneAmount() != null) {
            Optional<ProblemDetail> coverage = FundingValidations.milestoneCoversAllocations(
                    request.getMilestoneAmount(), allocationRepository.sumAllocatedByMilestoneId(milestoneId));
            if (coverage.isPresent()) {
                return Either.left(coverage.get());
            }
        }

        if (titleChanging) {
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

    public boolean belongsToProject(MilestoneEntity milestone, ProjectEntity project) {
        return milestone.getProject().getId().equals(project.getId());
    }

    public MilestoneView toView(MilestoneEntity milestone) {
        return MilestoneView.builder()
                .milestoneId(milestone.getId())
                .externalMilestoneId(milestone.getExternalMilestoneId())
                .projectId(milestone.getProject().getId())
                .milestoneTitle(milestone.getMilestoneTitle())
                .proId(milestone.getProId())
                .milestoneAmount(milestone.getMilestoneAmount())
                .currency(milestone.getCurrency())
                .milestoneDate(milestone.getMilestoneDate())
                .spentAmount(allocationRepository.spentAmountByMilestoneId(
                        milestone.getId(), EventType.SPENDING))
                .build();
    }

    /** proId and id are deliberately not set here — see where they're assigned in {@link #validateAndSave}. */
    private MilestoneEntity toEntity(MilestoneCreateRequest request, ProjectEntity project) {
        return MilestoneEntity.builder()
                .milestoneTitle(request.getMilestoneTitle())
                .milestoneAmount(request.getMilestoneAmount())
                .currency(request.getCurrency())
                .milestoneDate(request.getMilestoneDate())
                .project(project)
                .build();
    }

}
