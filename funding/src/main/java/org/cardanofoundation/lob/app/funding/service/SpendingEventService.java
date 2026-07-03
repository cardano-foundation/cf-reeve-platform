package org.cardanofoundation.lob.app.funding.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpendingEventService {

    private static final String SPENDING_EVENT_ALREADY_PUBLISHED = "SPENDING_EVENT_ALREADY_PUBLISHED";
    private static final String PROJECT_FIELDS_REQUIRED = "PROJECT_FIELDS_REQUIRED";

    private final FundingEventRepository fundingEventRepository;
    private final FundingProjectRepository projectRepository;
    private final MilestoneRepository milestoneRepository;
    private final EventMilestoneAllocationRepository milestoneAllocationRepository;
    private final KeycloakSecurityHelper keycloakSecurityHelper;

    // -------------------------------------------------------------------------
    // View-returning API (used by the controller — carries the ProblemDetail)
    // -------------------------------------------------------------------------

    public PagedResponse<SpendingEventView> listEvents(String organisationId, Optional<EventStatus> status,
            Optional<EventType> eventType, Pageable pageable) {
        if (!keycloakSecurityHelper.canUserAccessOrg(organisationId)) {
            return PagedResponse.error(Problems.unauthorized());
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
            return SpendingEventView.error(eventNotFound(eventId));
        }
        if (!keycloakSecurityHelper.canUserAccessOrg(eventM.get().getOrganisationId())) {
            return SpendingEventView.error(Problems.unauthorized());
        }
        return toView(eventM.get());
    }

    @Transactional
    public SpendingEventView createEvent(SpendingEventCreateRequest request) {
        return create(request).fold(SpendingEventView::error, this::toView);
    }

    @Transactional
    public SpendingEventView updateEvent(String eventId, SpendingEventCreateRequest request) {
        Optional<ProblemDetail> denied = denyIfNoEventAccess(eventId);
        if (denied.isPresent()) {
            return SpendingEventView.error(denied.get());
        }
        return update(eventId, request).fold(SpendingEventView::error, this::toView);
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

    private static ProblemDetail eventNotFound(String eventId) {
        return Problems.notFound("Event not found: " + eventId, ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
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
        if(fundingEventRepository.existsById(event.getId())) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Event already exists: %s".formatted(event.getId()));
            problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_ALREADY_EXISTS);
            return Either.left(problem);
        }

        Either<ProblemDetail, Void> allocResult = populateMilestoneAllocations(event, request.getAllocations(), request.getOrganisationId());
        if (allocResult.isLeft()) return Either.left(allocResult.getLeft());

        recalculateTotalAmount(event);
        Optional<ProblemDetail> totalProblem = FundingValidations.eventTotal(event.getEventType(), event.getTotalAmount());
        if (totalProblem.isPresent()) return Either.left(totalProblem.get());
        return Either.right(fundingEventRepository.saveAndFlush(event));
    }

    @Transactional
    public Either<ProblemDetail, FundingEventEntity> update(String eventId, SpendingEventCreateRequest request) {
        Either<ProblemDetail, FundingEventEntity> eventOrError = findEventOrError(eventId);
        if (eventOrError.isLeft()) return eventOrError;

        FundingEventEntity event = eventOrError.get();

        if (event.getStatus() == EventStatus.PUBLISHED) {
            log.warn("Cannot update published event: {}", eventId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Cannot update a published event: %s".formatted(eventId));
            problem.setTitle(SPENDING_EVENT_ALREADY_PUBLISHED);
            return Either.left(problem);
        }

        event.setFundingId(request.getFundingId());
        event.setFundingHash(request.getFundingHash());
        event.setFundingEntity(request.getFundingEntity());
        event.setCurrency(request.getCurrency());

        event.getMilestoneAllocations().clear();
        fundingEventRepository.flush();

        Either<ProblemDetail, Void> allocResult = populateMilestoneAllocations(event, request.getAllocations(), request.getOrganisationId());
        if (allocResult.isLeft()) return Either.left(allocResult.getLeft());

        recalculateTotalAmount(event);
        Optional<ProblemDetail> totalProblem = FundingValidations.eventTotal(event.getEventType(), event.getTotalAmount());
        if (totalProblem.isPresent()) return Either.left(totalProblem.get());
        return Either.right(fundingEventRepository.saveAndFlush(event));
    }

    @Transactional
    public Either<ProblemDetail, FundingEventEntity> publish(String eventId) {
        Either<ProblemDetail, FundingEventEntity> eventOrError = findEventOrError(eventId);
        if (eventOrError.isLeft()) return eventOrError;

        FundingEventEntity event = eventOrError.get();
        if (event.getStatus() == EventStatus.PUBLISHED) {
            log.warn("Event already published: {}", eventId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Event is already published: %s".formatted(eventId));
            problem.setTitle(SPENDING_EVENT_ALREADY_PUBLISHED);
            return Either.left(problem);
        }
        event.setStatus(EventStatus.PUBLISHED);
        event.setLedgerDispatchApproved(true);
        return Either.right(fundingEventRepository.saveAndFlush(event));
    }

    @Transactional
    public Either<ProblemDetail, Void> delete(String eventId) {
        Either<ProblemDetail, FundingEventEntity> eventOrError = findEventOrError(eventId);
        if (eventOrError.isLeft()) return Either.left(eventOrError.getLeft());

        FundingEventEntity event = eventOrError.get();
        if (event.getStatus() == EventStatus.PUBLISHED) {
            log.warn("Cannot delete published event: {}", eventId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Cannot delete a published event: %s".formatted(eventId));
            problem.setTitle(SPENDING_EVENT_ALREADY_PUBLISHED);
            return Either.left(problem);
        }
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
                .projectAllocations(projViews)
                .build();
    }

    public SpendingEventPublishView toPublishView(FundingEventEntity event) {
        LocalDate date = event.getCreatedAt().toLocalDate();

        List<SpendingEventPublishView.ProjectAllocation> projAllocations = buildPublishProjectAllocations(event.getId());

        return SpendingEventPublishView.builder()
                .eventId(event.getId())
                .organisationId(event.getOrganisationId())
                .eventType(event.getEventType())
                .date(date)
                .fundingId(event.getFundingId())
                .fundingHash(event.getFundingHash())
                .fundingEntity(event.getFundingEntity())
                .amount(event.getTotalAmount())
                .currency(toCurrency(event.getCurrency()))
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
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Event not found: %s".formatted(eventId));
            problem.setTitle("SPENDING_EVENT_NOT_FOUND");
            return Either.left(problem);
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

        for (EventProjectAllocationRequest req : allocationRequests) {
            Either<ProblemDetail, ProjectEntity> projectResult = resolveOrCreateProject(req, organisationId);
            if (projectResult.isLeft()) return Either.left(projectResult.getLeft());

            ProjectEntity project = projectResult.get();
            BigDecimal projectAllocatedTotal = BigDecimal.ZERO;

            for (EventMilestoneAllocationRequest milestoneReq : req.getMilestones()) {
                Either<ProblemDetail, MilestoneEntity> milestoneResult = resolveOrCreateMilestone(milestoneReq.getMilestone(), project);
                if (milestoneResult.isLeft()) return Either.left(milestoneResult.getLeft());

                MilestoneEntity milestone = milestoneResult.get();

                Optional<ProblemDetail> allocationProblem = FundingValidations.allocation(
                        milestoneReq.getAllocatedAmount(), milestone, event.getEventType());
                if (allocationProblem.isPresent()) return Either.left(allocationProblem.get());

                Optional<ProblemDetail> spendProblem = FundingValidations.spendDetail(
                        event.getEventType(), milestoneReq.getAllocatedAmount(),
                        milestoneReq.getCategory(), milestoneReq.getVendor(), milestoneReq.getAmountFcy(),
                        milestoneReq.getCurrency(), milestoneReq.getFxRate(), milestoneReq.getAmountRcy(),
                        milestoneReq.getSpendDate(), milestoneReq.getHash(), milestoneReq.getNotes());
                if (spendProblem.isPresent()) return Either.left(spendProblem.get());

                if (milestoneReq.getAllocatedAmount() != null) {
                    projectAllocatedTotal = projectAllocatedTotal.add(milestoneReq.getAllocatedAmount());
                }

                EventMilestoneAllocationEntity.Id id = new EventMilestoneAllocationEntity.Id(event.getId(), milestone.getId());

                event.getMilestoneAllocations().add(
                        EventMilestoneAllocationEntity.builder()
                                .id(id)
                                .allocatedAmount(milestoneReq.getAllocatedAmount())
                                .category(milestoneReq.getCategory())
                                .vendor(milestoneReq.getVendor())
                                .amountFcy(milestoneReq.getAmountFcy())
                                .currency(milestoneReq.getCurrency())
                                .fxRate(milestoneReq.getFxRate())
                                .amountRcy(milestoneReq.getAmountRcy())
                                .spendDate(milestoneReq.getSpendDate())
                                .hash(milestoneReq.getHash())
                                .notes(milestoneReq.getNotes())
                                .build());
            }

            Optional<ProblemDetail> totalProblem = FundingValidations.allocationTotal(projectAllocatedTotal, project);
            if (totalProblem.isPresent()) return Either.left(totalProblem.get());
        }
        return Either.right(null);
    }

    private Either<ProblemDetail, ProjectEntity> resolveOrCreateProject(EventProjectAllocationRequest req, String organisationId) {
        Either<ProblemDetail, ProjectEntity> rootResult = resolveOrCreateRootProject(req, organisationId);
        if (rootResult.isLeft()) return rootResult;

        ProjectEntity rootProject = rootResult.get();

        if (req.getSubProject() == null) {
            return Either.right(rootProject);
        }
        return resolveOrCreateSubProject(req.getSubProject(), rootProject);
    }

    private Either<ProblemDetail, ProjectEntity> resolveOrCreateRootProject(EventProjectAllocationRequest req, String organisationId) {
        if (req.getExternalProjectId() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "externalProjectId is required");
            problem.setTitle(PROJECT_FIELDS_REQUIRED);
            return Either.left(problem);
        }

        String projectId = ProjectEntity.id(organisationId, req.getExternalProjectId());
        if (projectRepository.existsById(projectId)) {
            return Either.right(projectRepository.findById(projectId).orElseThrow());
        }

        if (req.getProjectTitle() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "projectTitle is required when creating a new project");
            problem.setTitle(PROJECT_FIELDS_REQUIRED);
            return Either.left(problem);
        }

        if (req.getSubProject() == null && (req.getTotalAmount() == null || req.getCurrency() == null)) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "totalAmount and currency are required when creating a new root project");
            problem.setTitle(PROJECT_FIELDS_REQUIRED);
            return Either.left(problem);
        }

        if (req.getFundingId() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "fundingId is required when creating a new project");
            problem.setTitle(PROJECT_FIELDS_REQUIRED);
            return Either.left(problem);
        }

        Optional<ProblemDetail> amountProblem = FundingValidations.projectAmount(req.getTotalAmount());
        if (amountProblem.isPresent()) {
            return Either.left(amountProblem.get());
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

    private Either<ProblemDetail, ProjectEntity> resolveOrCreateSubProject(SubProjectRequest subReq, ProjectEntity parent) {
        if (subReq.getSubProjectId() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "subProjectId is required for sub-project resolution");
            problem.setTitle(PROJECT_FIELDS_REQUIRED);
            return Either.left(problem);
        }

        String subProjectUid = ProjectEntity.subId(parent.getId(), subReq.getSubProjectId());
        Optional<ProjectEntity> existing = projectRepository.findById(subProjectUid);
        if (existing.isPresent()) {
            return Either.right(existing.get());
        }

        Optional<ProblemDetail> structure = FundingValidations.subProjectAllowed(milestoneRepository.existsByProjectId(parent.getId()));
        if (structure.isPresent()) {
            return Either.left(structure.get());
        }

        if (subReq.getProjectTitle() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "projectTitle is required when creating a new sub-project");
            problem.setTitle(PROJECT_FIELDS_REQUIRED);
            return Either.left(problem);
        }

        ProjectEntity subProject = ProjectEntity.builder()
                .id(subProjectUid)
                .organisationId(parent.getOrganisationId())
                .externalProjectId(subReq.getSubProjectId())
                .projectTitle(subReq.getProjectTitle())
                .parentProject(parent)
                .build();
        return Either.right(projectRepository.saveAndFlush(subProject));
    }

    private Either<ProblemDetail, MilestoneEntity> resolveOrCreateMilestone(MilestoneCreateRequest req, ProjectEntity project) {
        if (req.getExternalMilestoneId() != null) {
            Optional<MilestoneEntity> existing = milestoneRepository.findByProjectIdAndExternalMilestoneId(project.getId(), req.getExternalMilestoneId());
            if (existing.isPresent()) {
                return Either.right(existing.get());
            }
            if (req.getMilestoneTitle() == null || req.getMilestoneAmount() == null
                    || req.getCurrency() == null || req.getMilestoneDate() == null) {
                log.warn("Milestone not found: {} in project: {}", req.getExternalMilestoneId(), project.getId());
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Milestone not found: %s".formatted(req.getExternalMilestoneId()));
                problem.setTitle("MILESTONE_NOT_FOUND");
                return Either.left(problem);
            }
        } else if (req.getMilestoneTitle() == null || req.getMilestoneAmount() == null
                || req.getCurrency() == null || req.getMilestoneDate() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "milestoneTitle, milestoneAmount, currency, milestoneDate are required when creating a new milestone");
            problem.setTitle("MILESTONE_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        Optional<ProblemDetail> structure = FundingValidations.milestoneAllowed(projectRepository.existsByParentProjectId(project.getId()));
        if (structure.isPresent()) {
            return Either.left(structure.get());
        }

        BigDecimal otherMilestonesTotal = FundingValidations.sumMilestoneAmounts(
                milestoneRepository.findByProjectId(project.getId()), null);
        Optional<ProblemDetail> validation = FundingValidations.milestone(
                req.getMilestoneAmount(), req.getMilestoneDate(), project, otherMilestonesTotal);
        if (validation.isPresent()) {
            return Either.left(validation.get());
        }

        MilestoneEntity newMilestone = MilestoneEntity.builder()
                .id(milestoneId(project.getId(), req))
                .externalMilestoneId(req.getExternalMilestoneId())
                .milestoneTitle(req.getMilestoneTitle())
                .milestoneAmount(req.getMilestoneAmount())
                .currency(req.getCurrency())
                .milestoneDate(req.getMilestoneDate())
                .project(project)
                .build();
        return Either.right(milestoneRepository.saveAndFlush(newMilestone));
    }

    /**
     * Deterministic milestone id: the natural key {@code (projectId, externalMilestoneId)} when a
     * user-defined id is supplied, otherwise a content hash for an anonymous milestone.
     */
    private static String milestoneId(String projectId, MilestoneCreateRequest req) {
        return req.getExternalMilestoneId() != null
                ? MilestoneEntity.id(projectId, req.getExternalMilestoneId())
                : MilestoneEntity.contentId(projectId, req.getMilestoneTitle(), req.getMilestoneAmount(),
                        req.getCurrency(), req.getMilestoneDate());
    }

    /** The event total is the sum of its milestone allocations (all event types). */
    private void recalculateTotalAmount(FundingEventEntity event) {
        BigDecimal total = event.getMilestoneAllocations().stream()
                .map(EventMilestoneAllocationEntity::getAllocatedAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        event.setTotalAmount(total);
    }

    /** Groups flat milestone allocations by their project, preserving insertion order. */
    private Map<ProjectEntity, List<EventMilestoneAllocationEntity>> groupByProject(String eventId) {
        return milestoneAllocationRepository.findById_EventId(eventId).stream()
                .filter(a -> {
                    MilestoneEntity m = milestoneRepository.findById(a.getId().getMilestoneId()).orElse(null);
                    return m != null && m.getProject() != null;
                })
                .collect(Collectors.groupingBy(
                        a -> milestoneRepository.findById(a.getId().getMilestoneId()).orElseThrow().getProject(),
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    private List<EventProjectAllocationView> buildProjectAllocationViews(String eventId) {
        return groupByProject(eventId).entrySet().stream()
                .map(entry -> {
                    ProjectEntity project = entry.getKey();
                    List<EventMilestoneAllocationView> mViews = entry.getValue().stream()
                            .map(this::toMilestoneAllocationView)
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
        return groupByProject(eventId).entrySet().stream()
                .map(entry -> {
                    ProjectEntity project = entry.getKey();
                    List<SpendingEventPublishView.Milestone> milestones = entry.getValue().stream()
                            .map(this::toPublishMilestone)
                            .toList();
                    // When the allocation targets a sub-project, publish the root project's id/title and
                    // surface the sub-project's title separately; otherwise the project itself is the root.
                    boolean isSubProject = project.getParentProject() != null;
                    ProjectEntity root = isSubProject ? project.getParentProject() : project;
                    return SpendingEventPublishView.ProjectAllocation.builder()
                            .externalProjectId(root.getExternalProjectId())
                            .projectTitle(root.getProjectTitle())
                            .subProjectTitle(isSubProject ? project.getProjectTitle() : null)
                            .milestones(milestones)
                            .build();
                })
                .toList();
    }

    private EventMilestoneAllocationView toMilestoneAllocationView(EventMilestoneAllocationEntity alloc) {
        MilestoneEntity milestone = milestoneRepository.findById(alloc.getId().getMilestoneId()).orElse(null);
        return EventMilestoneAllocationView.builder()
                .eventId(alloc.getId().getEventId())
                .milestoneId(alloc.getId().getMilestoneId())
                .externalMilestoneId(milestone != null ? milestone.getExternalMilestoneId() : null)
                .milestoneTitle(milestone != null ? milestone.getMilestoneTitle() : null)
                .milestoneAmount(milestone != null ? milestone.getMilestoneAmount() : null)
                .allocatedAmount(alloc.getAllocatedAmount())
                .currency(milestone != null ? milestone.getCurrency() : null)
                .milestoneDate(milestone != null ? milestone.getMilestoneDate() : null)
                .category(alloc.getCategory())
                .vendor(alloc.getVendor())
                .amountFcy(alloc.getAmountFcy())
                .spendCurrency(alloc.getCurrency())
                .fxRate(alloc.getFxRate())
                .amountRcy(alloc.getAmountRcy())
                .spendDate(alloc.getSpendDate())
                .hash(alloc.getHash())
                .notes(alloc.getNotes())
                .build();
    }

    private SpendingEventPublishView.Milestone toPublishMilestone(EventMilestoneAllocationEntity alloc) {
        MilestoneEntity milestone = milestoneRepository.findById(alloc.getId().getMilestoneId()).orElse(null);
        return SpendingEventPublishView.Milestone.builder()
                .milestoneId(alloc.getId().getMilestoneId())
                .milestoneTitle(milestone != null ? milestone.getMilestoneTitle() : null)
                .milestoneAmount(milestone != null ? milestone.getMilestoneAmount() : null)
                .allocatedAmount(alloc.getAllocatedAmount())
                .currency(milestone != null ? toCurrency(milestone.getCurrency()) : null)
                .milestoneDate(milestone != null ? milestone.getMilestoneDate() : null)
                .category(alloc.getCategory())
                .vendor(alloc.getVendor())
                .amountFcy(alloc.getAmountFcy())
                .spendCurrency(alloc.getCurrency() != null ? toCurrency(alloc.getCurrency()) : null)
                .fxRate(alloc.getFxRate())
                .amountRcy(alloc.getAmountRcy())
                .spendDate(alloc.getSpendDate())
                .documentHash(alloc.getHash())
                .notes(alloc.getNotes())
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
