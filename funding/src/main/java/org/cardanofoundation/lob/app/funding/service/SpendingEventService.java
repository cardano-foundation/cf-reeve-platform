package org.cardanofoundation.lob.app.funding.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.funding.domain.entity.*;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.*;
import org.cardanofoundation.lob.app.funding.domain.view.*;
import org.cardanofoundation.lob.app.funding.repository.*;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.funding.util.FundingValidations;
import org.cardanofoundation.lob.app.funding.util.Problems;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpendingEventService {

    private final FundingEventRepository fundingEventRepository;
    private final FundingProjectRepository projectRepository;
    private final EventMilestoneAllocationRepository milestoneAllocationRepository;
    private final MilestoneService milestoneService;
    private final ProjectStructureService projectStructureService;
    private final KeycloakSecurityHelper keycloakSecurityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;

    // -------------------------------------------------------------------------
    // View-returning API (used by the controller — carries the ProblemDetail)
    // -------------------------------------------------------------------------

    public PagedResponse<SpendingEventView> listEvents(String organisationId, Optional<EventStatus> status,
            Optional<EventType> eventType, Pageable pageable) {
        if (!keycloakSecurityHelper.canUserAccessOrg(organisationId)) {
            return PagedResponse.error(Problems.unauthorized());
        }
        if (organisationPublicApi.findByOrganisationId(organisationId).isEmpty()) {
            return PagedResponse.error(Problems.organisationNotFound(organisationId));
        }
        return PagedResponse.of(findByOrganisationIdAndFilter(organisationId, status, eventType, pageable), this::toView);
    }

    public PagedResponse<SpendingEventView> listEventsByProject(String projectId, Optional<EventStatus> status,
            Optional<EventType> eventType, Pageable pageable) {
        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);
        if (projectM.isEmpty()) {
            return PagedResponse.error(Problems.notFound("Project not found: " + projectId, ErrorTitleConstants.PROJECT_NOT_FOUND));
        }
        if (!keycloakSecurityHelper.canUserAccessOrg(projectM.get().getOrganisationId())) {
            return PagedResponse.error(Problems.unauthorized());
        }
        return PagedResponse.of(findByProjectIdAndFilter(projectId, status, eventType, pageable), this::toView);
    }

    public SpendingEventView getEvent(String eventId) {
        Optional<FundingEventEntity> eventM = fundingEventRepository.findById(eventId);
        if (eventM.isEmpty()) {
            return SpendingEventView.error(Problems.eventNotFound(eventId));
        }
        if (!keycloakSecurityHelper.canUserAccessOrg(eventM.get().getOrganisationId())) {
            return SpendingEventView.error(Problems.unauthorized());
        }
        return toView(eventM.get());
    }

    @Transactional
    public SpendingEventView createEvent(SpendingEventCreateRequest request) {
        return create(request).fold(this::rollbackAndError, this::toView);
    }

    @Transactional
    public SpendingEventView updateEvent(String eventId, SpendingEventCreateRequest request) {
        Optional<ProblemDetail> denied = denyIfNoEventAccess(eventId);
        if (denied.isPresent()) {
            return SpendingEventView.error(denied.get());
        }
        return update(eventId, request).fold(this::rollbackAndError, this::toView);
    }

    /**
     * Builds an error view and marks the (owning) transaction rollback-only, so that any project or
     * milestone auto-created while resolving the event's allocations is not left behind when a later
     * validation fails. Rolling back from the owner is a <em>local</em> rollback — it does not raise
     * {@code UnexpectedRollbackException}.
     */
    private SpendingEventView rollbackAndError(ProblemDetail problem) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
        return SpendingEventView.error(problem);
    }

    @Transactional
    public SpendingEventView publishEvent(String eventId) {
        Optional<ProblemDetail> denied = denyIfNoEventAccess(eventId);
        if (denied.isPresent()) {
            return SpendingEventView.error(denied.get());
        }
        return publish(eventId).fold(SpendingEventView::error, this::toView);
    }

    @Transactional
    public Optional<ProblemDetail> deleteEvent(String eventId) {
        Optional<ProblemDetail> denied = denyIfNoEventAccess(eventId);
        if (denied.isPresent()) {
            return denied;
        }
        return delete(eventId).fold(Optional::of, ignored -> Optional.empty());
    }

    /** 401 when the event exists and the caller cannot access its organisation; empty otherwise. */
    private Optional<ProblemDetail> denyIfNoEventAccess(String eventId) {
        Optional<FundingEventEntity> eventM = fundingEventRepository.findById(eventId);
        if (eventM.isPresent() && !keycloakSecurityHelper.canUserAccessOrg(eventM.get().getOrganisationId())) {
            return Optional.of(Problems.unauthorized());
        }
        return Optional.empty();
    }

    public Optional<FundingEventEntity> findById(String eventId) {
        return fundingEventRepository.findById(eventId);
    }

    public Page<FundingEventEntity> findByOrganisationIdAndFilter(
            String organisationId,
            Optional<EventStatus> status,
            Optional<EventType> eventType,
            Pageable pageable) {
        return fundingEventRepository.findByOrganisationIdAndFilter(
                organisationId,
                status.orElse(null),
                eventType.orElse(null),
                pageable);
    }

    public Page<FundingEventEntity> findByProjectIdAndFilter(
            String projectId,
            Optional<EventStatus> status,
            Optional<EventType> eventType,
            Pageable pageable) {
        return fundingEventRepository.findByProjectIdAndFilter(
                projectId,
                status.orElse(null),
                eventType.orElse(null),
                pageable);
    }

    @Transactional
    public Either<ProblemDetail, FundingEventEntity> create(SpendingEventCreateRequest request) {
        FundingEventEntity event = toEntity(request);
        if (fundingEventRepository.existsById(event.getId())) {
            return Either.left(Problems.conflict("Event already exists: %s".formatted(event.getId()),
                    ErrorTitleConstants.SPENDING_EVENT_ALREADY_EXISTS));
        }
        return validateAndPersist(event, request);
    }

    @Transactional
    public Either<ProblemDetail, FundingEventEntity> update(String eventId, SpendingEventCreateRequest request) {
        Either<ProblemDetail, FundingEventEntity> eventOrError = findEventOrError(eventId);
        if (eventOrError.isLeft()) return eventOrError;

        FundingEventEntity event = eventOrError.get();

        Optional<ProblemDetail> draftProblem = requireDraft(event, "Cannot update a published event: %s");
        if (draftProblem.isPresent()) return Either.left(draftProblem.get());

        // The event's identity — organisation and type — is fixed at creation; the update payload
        // must not silently target another organisation's projects or change the event's semantics.
        if (!event.getOrganisationId().equals(request.getOrganisationId())) {
            return Either.left(Problems.badRequest(
                    "organisationId %s does not match the event's organisation".formatted(request.getOrganisationId()),
                    ErrorTitleConstants.ORGANISATION_MISMATCH));
        }
        if (event.getEventType() != request.getEventType()) {
            return Either.left(Problems.badRequest(
                    "eventType cannot be changed on update (event is %s)".formatted(event.getEventType()),
                    ErrorTitleConstants.EVENT_TYPE_IMMUTABLE));
        }

        event.setFundingId(request.getFundingId());
        event.setFundingHash(request.getFundingHash());
        event.setFundingEntity(request.getFundingEntity());
        event.setCurrency(request.getCurrency());
        event.setEventDate(request.getEventDate());
        applySpendDetail(event, request);

        event.getMilestoneAllocations().clear();
        fundingEventRepository.flush();

        return validateAndPersist(event, request);
    }

    /** Validation pipeline shared by create and update: spend detail, allocations, event totals. */
    private Either<ProblemDetail, FundingEventEntity> validateAndPersist(FundingEventEntity event, SpendingEventCreateRequest request) {
        Optional<ProblemDetail> entityProblem = FundingValidations.fundingEntity(event.getEventType(), event.getFundingEntity());
        if (entityProblem.isPresent()) return Either.left(entityProblem.get());

        Optional<ProblemDetail> spendProblem = validateEventSpendDetail(event);
        if (spendProblem.isPresent()) return Either.left(spendProblem.get());

        Either<ProblemDetail, Void> allocResult = populateMilestoneAllocations(event, request.getAllocations(), request.getOrganisationId());
        if (allocResult.isLeft()) return Either.left(allocResult.getLeft());

        recalculateTotalAmount(event);
        Optional<ProblemDetail> totalProblem = validateEventTotals(event);
        if (totalProblem.isPresent()) return Either.left(totalProblem.get());
        return Either.right(fundingEventRepository.saveAndFlush(event));
    }

    /** Published events are immutable — returns a conflict built from {@code messageTemplate} otherwise empty. */
    private static Optional<ProblemDetail> requireDraft(FundingEventEntity event, String messageTemplate) {
        if (event.getStatus() == EventStatus.PUBLISHED) {
            String message = messageTemplate.formatted(event.getId());
            log.warn(message);
            return Optional.of(Problems.conflict(message, ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED));
        }
        return Optional.empty();
    }

    private static void applySpendDetail(FundingEventEntity event, SpendingEventCreateRequest request) {
        event.setCategory(request.getCategory());
        event.setVendor(request.getVendor());
        event.setAmountFcy(request.getAmountFcy());
        event.setAmountRcy(request.getAmountRcy());
        event.setSpendCurrency(request.getSpendCurrency());
        event.setFxRate(request.getFxRate());
        event.setHash(request.getHash());
        event.setNotes(request.getNotes());
    }

    private static Optional<ProblemDetail> validateEventSpendDetail(FundingEventEntity event) {
        return FundingValidations.spendDetail(event.getEventType(),
                event.getCategory(), event.getVendor(), event.getAmountFcy(), event.getSpendCurrency(),
                event.getFxRate(), event.getAmountRcy(), event.getHash(), event.getNotes());
    }

    private static Optional<ProblemDetail> validateEventTotals(FundingEventEntity event) {
        Optional<ProblemDetail> allocated = FundingValidations.spendFullyAllocated(
                event.getEventType(), event.getTotalAmount(), event.getAmountRcy());
        if (allocated.isPresent()) return allocated;
        return FundingValidations.eventTotal(event.getEventType(), event.getTotalAmount());
    }

    @Transactional
    public Either<ProblemDetail, FundingEventEntity> publish(String eventId) {
        Either<ProblemDetail, FundingEventEntity> eventOrError = findEventOrError(eventId);
        if (eventOrError.isLeft()) return eventOrError;

        FundingEventEntity event = eventOrError.get();
        Optional<ProblemDetail> draftProblem = requireDraft(event, "Event is already published: %s");
        if (draftProblem.isPresent()) return Either.left(draftProblem.get());

        event.setStatus(EventStatus.PUBLISHED);
        event.setLedgerDispatchApproved(true);
        return Either.right(fundingEventRepository.saveAndFlush(event));
    }

    @Transactional
    public Either<ProblemDetail, Void> delete(String eventId) {
        Either<ProblemDetail, FundingEventEntity> eventOrError = findEventOrError(eventId);
        if (eventOrError.isLeft()) return Either.left(eventOrError.getLeft());

        FundingEventEntity event = eventOrError.get();
        Optional<ProblemDetail> draftProblem = requireDraft(event, "Cannot delete a published event: %s");
        if (draftProblem.isPresent()) return Either.left(draftProblem.get());

        fundingEventRepository.delete(event);
        return Either.right(null);
    }

    // -------------------------------------------------------------------------
    // View builders
    // -------------------------------------------------------------------------

    public SpendingEventView toView(FundingEventEntity event) {
        List<EventProjectAllocationView> projViews = buildProjectAllocationViews(event.getId());

        return SpendingEventView.builder()
                .eventId(event.getId())
                .organisationId(event.getOrganisationId())
                .eventType(event.getEventType())
                .status(event.getStatus())
                .fundingId(event.getFundingId())
                .totalAmount(event.getTotalAmount())
                .currency(event.getCurrency())
                .txHash(event.getTxHash())
                .ledgerDispatchStatus(event.getLedgerDispatchStatus())
                .fundingHash(event.getFundingHash())
                .fundingEntity(event.getFundingEntity())
                .eventDate(event.getEventDate())
                .category(event.getCategory())
                .vendor(event.getVendor())
                .amountFcy(event.getAmountFcy())
                .spendCurrency(event.getSpendCurrency())
                .fxRate(event.getFxRate())
                .amountRcy(event.getAmountRcy())
                .hash(event.getHash())
                .notes(event.getNotes())
                .projectAllocations(projViews)
                .build();
    }

    public SpendingEventPublishView toPublishView(FundingEventEntity event) {
        List<SpendingEventPublishView.ProjectAllocation> projAllocations = buildPublishProjectAllocations(event.getId());

        return SpendingEventPublishView.builder()
                .eventId(event.getId())
                .organisationId(event.getOrganisationId())
                .eventType(event.getEventType())
                .eventDate(event.getEventDate())
                .fundingId(event.getFundingId())
                .fundingHash(event.getFundingHash())
                .fundingEntity(event.getFundingEntity())
                .amount(event.getTotalAmount())
                .currency(toCurrency(event.getCurrency()))
                .category(event.getCategory())
                .vendor(event.getVendor())
                .amountFcy(event.getAmountFcy())
                .spendCurrency(event.getSpendCurrency() != null ? toCurrency(event.getSpendCurrency()) : null)
                .fxRate(event.getFxRate())
                .amountRcy(event.getAmountRcy())
                .documentHash(event.getHash())
                .notes(event.getNotes())
                .projectAllocations(projAllocations)
                .build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Either<ProblemDetail, FundingEventEntity> findEventOrError(String eventId) {
        Optional<FundingEventEntity> eventM = fundingEventRepository.findById(eventId);
        if (eventM.isEmpty()) {
            log.warn("Event not found: {}", eventId);
            return Either.left(Problems.eventNotFound(eventId));
        }
        return Either.right(eventM.get());
    }

    /**
     * For each allocation request (project + milestones), resolves/creates the project and its
     * milestones, then adds a flat {@link EventMilestoneAllocationEntity} per milestone directly
     * to the event. The project association is implicit via the milestone's project FK.
     */
    private Either<ProblemDetail, Void> populateMilestoneAllocations(
            FundingEventEntity event,
            List<EventProjectAllocationRequest> allocationRequests,
            String organisationId) {

        // Combined budgets the event books against — a SPENDING event's spend (amountRcy) may not exceed
        // the summed milestone budgets nor the summed project budgets. A null budget anywhere lifts that
        // bound (it cannot be meaningfully enforced).
        BudgetAccumulator budget = new BudgetAccumulator();

        for (EventProjectAllocationRequest req : allocationRequests) {
            Either<ProblemDetail, ProjectEntity> rootResult = resolveOrCreateRootProject(req, organisationId);
            if (rootResult.isLeft()) return Either.left(rootResult.getLeft());

            Optional<ProblemDetail> nodeProblem = populateNode(
                    event, rootResult.get(), req.getMilestones(), req.getSubProjects(), budget);
            if (nodeProblem.isPresent()) return Either.left(nodeProblem.get());
        }

        Optional<ProblemDetail> capProblem = FundingValidations.eventAmountWithinBudget(
                event.getEventType(), event.getAmountRcy(),
                budget.milestoneKnown ? budget.milestoneBudget : null,
                budget.projectKnown ? budget.projectBudget : null);
        if (capProblem.isPresent()) return Either.left(capProblem.get());

        return Either.right(null);
    }

    /**
     * Recursively attaches an allocation node to the event, mirroring the create-project endpoint's tree:
     * a node resolves/creates <em>either</em> its milestones (each carrying an allocated amount)
     * <em>or</em> its sub-projects (never both). Budgets are accumulated for the event-amount cap.
     */
    private Optional<ProblemDetail> populateNode(FundingEventEntity event, ProjectEntity project,
            List<EventMilestoneAllocationRequest> milestones, List<EventSubProjectAllocationRequest> subProjects,
            BudgetAccumulator budget) {

        Optional<ProblemDetail> xor = FundingValidations.milestonesXorSubProjects(
                !milestones.isEmpty(), !subProjects.isEmpty());
        if (xor.isPresent()) {
            return xor;
        }

        if (!milestones.isEmpty()) {
            // A node carrying allocations is a target project — its budget bounds the event amount.
            if (project.getTotalAmount() != null) {
                budget.projectBudget = budget.projectBudget.add(project.getTotalAmount());
            } else {
                budget.projectKnown = false;
            }

            BigDecimal projectAllocatedTotal = BigDecimal.ZERO;
            for (EventMilestoneAllocationRequest milestoneReq : milestones) {
                Either<ProblemDetail, MilestoneEntity> milestoneResult = milestoneService.resolveOrCreate(project, milestoneReq.getMilestone());
                if (milestoneResult.isLeft()) return Optional.of(milestoneResult.getLeft());

                MilestoneEntity milestone = milestoneResult.get();

                Optional<ProblemDetail> allocationProblem = FundingValidations.allocation(
                        milestoneReq.getAllocatedAmount(), milestone, event.getEventType());
                if (allocationProblem.isPresent()) return allocationProblem;

                if (milestoneReq.getAllocatedAmount() != null) {
                    projectAllocatedTotal = projectAllocatedTotal.add(milestoneReq.getAllocatedAmount());
                }
                if (milestone.getMilestoneAmount() != null) {
                    budget.milestoneBudget = budget.milestoneBudget.add(milestone.getMilestoneAmount());
                } else {
                    budget.milestoneKnown = false;
                }

                event.getMilestoneAllocations().add(EventMilestoneAllocationEntity.builder()
                        .id(new EventMilestoneAllocationEntity.Id(event.getId(), milestone.getId()))
                        .allocatedAmount(milestoneReq.getAllocatedAmount())
                        .build());
            }

            Optional<ProblemDetail> totalProblem = FundingValidations.allocationTotal(projectAllocatedTotal, project);
            if (totalProblem.isPresent()) return totalProblem;
        }

        for (EventSubProjectAllocationRequest subNode : subProjects) {
            Either<ProblemDetail, ProjectEntity> subResult = resolveOrCreateSubProjectNode(subNode, project);
            if (subResult.isLeft()) return Optional.of(subResult.getLeft());

            Optional<ProblemDetail> childProblem = populateNode(
                    event, subResult.get(), subNode.getMilestones(), subNode.getSubProjects(), budget);
            if (childProblem.isPresent()) return childProblem;
        }
        return Optional.empty();
    }

    /** Mutable holder for the event-amount cap budgets accumulated while walking the allocation tree. */
    private static final class BudgetAccumulator {
        private BigDecimal milestoneBudget = BigDecimal.ZERO;
        private boolean milestoneKnown = true;
        private BigDecimal projectBudget = BigDecimal.ZERO;
        private boolean projectKnown = true;
    }

    private Either<ProblemDetail, ProjectEntity> resolveOrCreateRootProject(EventProjectAllocationRequest req, String organisationId) {
        if (req.getExternalProjectId() == null) {
            return Either.left(Problems.badRequest("externalProjectId is required",
                    ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }

        String projectId = ProjectEntity.id(organisationId, req.getExternalProjectId());
        if (projectRepository.existsById(projectId)) {
            return Either.right(projectRepository.findById(projectId).orElseThrow());
        }

        // Id supplied but no project exists for it. With no creation fields, the caller is referencing
        // an existing project — fail as not-found. Supplying projectTitle (and budget) creates it instead.
        if (req.getProjectTitle() == null) {
            return Either.left(Problems.notFound(
                    "Project not found: %s. Supply projectTitle (and totalAmount/currency) to create it."
                            .formatted(req.getExternalProjectId()),
                    ErrorTitleConstants.PROJECT_NOT_FOUND));
        }

        // A root that directly carries milestones needs a budget; one that only holds sub-projects may omit it.
        if (req.getSubProjects().isEmpty() && (req.getTotalAmount() == null || req.getCurrency() == null)) {
            return Either.left(Problems.badRequest("totalAmount and currency are required when creating a new root project",
                    ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }

        Optional<ProblemDetail> amountProblem = FundingValidations.projectAmount(req.getTotalAmount());
        if (amountProblem.isPresent()) {
            return Either.left(amountProblem.get());
        }
        Optional<ProblemDetail> fundingIdProblem = projectStructureService.fundingIdAvailable(organisationId, req.getFundingId());
        if (fundingIdProblem.isPresent()) {
            return Either.left(fundingIdProblem.get());
        }
        // Root titles are unique per organisation — return a clean 409 rather than a DB-integrity 500.
        if (projectRepository.existsByOrganisationIdAndProjectTitleAndParentProjectIsNull(organisationId, req.getProjectTitle())) {
            return Either.left(Problems.conflict(
                    "Project title already exists in this organisation: " + req.getProjectTitle(),
                    ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS));
        }

        ProjectEntity newProject = ProjectEntity.builder()
                .id(projectId)
                .organisationId(organisationId)
                .fundingId(req.getFundingId())
                .externalProjectId(req.getExternalProjectId())
                .projectTitle(req.getProjectTitle())
                .totalAmount(req.getTotalAmount())
                .currency(req.getCurrency())
                .build();
        return Either.right(projectRepository.saveAndFlush(newProject));
    }

    private Either<ProblemDetail, ProjectEntity> resolveOrCreateSubProjectNode(EventSubProjectAllocationRequest subReq, ProjectEntity parent) {
        String subProjectUid = ProjectEntity.subId(parent.getId(), subReq.getExternalProjectId());
        Optional<ProjectEntity> existing = projectRepository.findById(subProjectUid);
        if (existing.isPresent()) {
            return Either.right(existing.get());
        }

        // Id supplied but no sub-project exists for it under this parent. With no creation fields, the
        // caller is referencing an existing sub-project — fail as not-found. Supplying projectTitle creates it.
        if (subReq.getProjectTitle() == null) {
            return Either.left(Problems.notFound(
                    "Sub-project not found: %s. Supply projectTitle to create it."
                            .formatted(subReq.getExternalProjectId()),
                    ErrorTitleConstants.PROJECT_NOT_FOUND));
        }

        // Same shared creation path (structure + budget rules) as the create-project endpoint.
        return projectStructureService.createSubProject(parent, subReq.getExternalProjectId(),
                subReq.getProjectTitle(), subReq.getFundingId(), subReq.getTotalAmount(), subReq.getCurrency());
    }

    /** The event total is the sum of its milestone allocations (all event types). */
    private void recalculateTotalAmount(FundingEventEntity event) {
        BigDecimal total = event.getMilestoneAllocations().stream()
                .map(EventMilestoneAllocationEntity::getAllocatedAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        event.setTotalAmount(total);
    }

    /** An event allocation with its milestone resolved once — the milestone is never null. */
    private record AllocatedMilestone(EventMilestoneAllocationEntity allocation, MilestoneEntity milestone) {
    }

    /** Loads the event's allocations, resolves each milestone once, groups by project (insertion order). */
    private Map<ProjectEntity, List<AllocatedMilestone>> allocationsByProject(String eventId) {
        return milestoneAllocationRepository.findById_EventId(eventId).stream()
                .map(alloc -> new AllocatedMilestone(alloc,
                        milestoneService.findById(alloc.getId().getMilestoneId()).orElse(null)))
                .filter(am -> am.milestone() != null && am.milestone().getProject() != null)
                .collect(Collectors.groupingBy(
                        am -> am.milestone().getProject(),
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    private List<EventProjectAllocationView> buildProjectAllocationViews(String eventId) {
        return allocationsByProject(eventId).entrySet().stream()
                .map(entry -> {
                    ProjectEntity project = entry.getKey();
                    List<EventMilestoneAllocationView> mViews = entry.getValue().stream()
                            .map(SpendingEventService::toMilestoneAllocationView)
                            .toList();
                    return EventProjectAllocationView.builder()
                            .projectId(project.getId())
                            .externalProjectId(project.getExternalProjectId())
                            .projectTitle(project.getProjectTitle())
                            .parentProjectId(project.getParentProject() != null ? project.getParentProject().getId() : null)
                            .milestoneAllocations(mViews)
                            .build();
                })
                .toList();
    }

    private List<SpendingEventPublishView.ProjectAllocation> buildPublishProjectAllocations(String eventId) {
        return allocationsByProject(eventId).entrySet().stream()
                .map(entry -> {
                    ProjectEntity project = entry.getKey();
                    List<SpendingEventPublishView.Milestone> milestones = entry.getValue().stream()
                            .map(SpendingEventService::toPublishMilestone)
                            .toList();
                    // Publish the root project's id/title as is. A direct allocation carries its
                    // milestones at the project level; an allocation to a sub-project nests the
                    // sub-project's own id/title/milestones so it is unambiguous where the money went.
                    boolean isSubProject = project.getParentProject() != null;
                    ProjectEntity root = rootOf(project);
                    return SpendingEventPublishView.ProjectAllocation.builder()
                            .externalProjectId(root.getExternalProjectId())
                            .projectTitle(root.getProjectTitle())
                            .subProject(isSubProject
                                    ? SpendingEventPublishView.SubProject.builder()
                                            .subProjectId(project.getExternalProjectId())
                                            .subProjectTitle(project.getProjectTitle())
                                            .milestones(milestones)
                                            .build()
                                    : null)
                            .milestones(isSubProject ? null : milestones)
                            .build();
                })
                .toList();
    }

    /** The top-level ancestor of a project — the project itself when it has no parent. */
    private static ProjectEntity rootOf(ProjectEntity project) {
        ProjectEntity cursor = project;
        while (cursor.getParentProject() != null) {
            cursor = cursor.getParentProject();
        }
        return cursor;
    }

    private static EventMilestoneAllocationView toMilestoneAllocationView(AllocatedMilestone am) {
        return EventMilestoneAllocationView.builder()
                .eventId(am.allocation().getId().getEventId())
                .milestoneId(am.allocation().getId().getMilestoneId())
                .externalMilestoneId(am.milestone().getExternalMilestoneId())
                .milestoneTitle(am.milestone().getMilestoneTitle())
                .milestoneAmount(am.milestone().getMilestoneAmount())
                .allocatedAmount(am.allocation().getAllocatedAmount())
                .currency(am.milestone().getCurrency())
                .milestoneDate(am.milestone().getMilestoneDate())
                .build();
    }

    private static SpendingEventPublishView.Milestone toPublishMilestone(AllocatedMilestone am) {
        return SpendingEventPublishView.Milestone.builder()
                .milestoneId(am.allocation().getId().getMilestoneId())
                .milestoneTitle(am.milestone().getMilestoneTitle())
                .milestoneAmount(am.milestone().getMilestoneAmount())
                .allocatedAmount(am.allocation().getAllocatedAmount())
                .currency(toCurrency(am.milestone().getCurrency()))
                .milestoneDate(am.milestone().getMilestoneDate())
                .build();
    }

    private FundingEventEntity toEntity(SpendingEventCreateRequest request) {
        return FundingEventEntity.builder()
                .id(FundingEventEntity.id(
                        request.getOrganisationId(),
                        request.getEventType(),
                        request.getFundingId(),
                        request.getFundingHash(),
                        request.getCurrency()))
                .eventType(request.getEventType())
                .status(EventStatus.DRAFT)
                .organisationId(request.getOrganisationId())
                .fundingId(request.getFundingId())
                .fundingHash(request.getFundingHash())
                .fundingEntity(request.getFundingEntity())
                .currency(request.getCurrency())
                .eventDate(request.getEventDate())
                .category(request.getCategory())
                .vendor(request.getVendor())
                .amountFcy(request.getAmountFcy())
                .amountRcy(request.getAmountRcy())
                .spendCurrency(request.getSpendCurrency())
                .fxRate(request.getFxRate())
                .hash(request.getHash())
                .notes(request.getNotes())
                .build();
    }

    private static SpendingEventPublishView.Currency toCurrency(String currencyCode) {
        if (currencyCode == null) return null;
        if (currencyCode.startsWith("ISO_")) {
            String custCode = currencyCode.substring(currencyCode.lastIndexOf(':') + 1);
            return SpendingEventPublishView.Currency.builder().id(currencyCode).custCode(custCode).build();
        }
        return SpendingEventPublishView.Currency.builder()
                .id("ISO_4217:" + currencyCode)
                .custCode(currencyCode)
                .build();
    }

}
