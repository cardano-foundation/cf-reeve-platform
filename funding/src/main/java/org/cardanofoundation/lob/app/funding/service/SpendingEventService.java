package org.cardanofoundation.lob.app.funding.service;

import java.math.BigDecimal;
import java.time.LocalDate;
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

import org.cardanofoundation.lob.app.funding.domain.entity.*;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.*;
import org.cardanofoundation.lob.app.funding.domain.view.*;
import org.cardanofoundation.lob.app.funding.repository.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpendingEventService {

    private static final String SPENDING_EVENT_ALREADY_PUBLISHED = "SPENDING_EVENT_ALREADY_PUBLISHED";

    private final FundingEventRepository fundingEventRepository;
    private final FundingProjectRepository projectRepository;
    private final MilestoneRepository milestoneRepository;
    private final SpendingItemRepository spendingItemRepository;
    private final EventProjectAllocationRepository allocationRepository;
    private final EventMilestoneAllocationRepository milestoneAllocationRepository;

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

        Either<ProblemDetail, Void> allocResult = populateProjectAllocations(event, request.getAllocations(), request.getOrganisationId());
        if (allocResult.isLeft()) return Either.left(allocResult.getLeft());

        if (request.getEventType() == EventType.SPENDING) {
            populateSpendingItems(event, request.getItems());
        }

        recalculateTotalAmount(event);
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

        event.getProjectAllocations().clear();
        fundingEventRepository.flush();

        Either<ProblemDetail, Void> allocResult = populateProjectAllocations(event, request.getAllocations(), request.getOrganisationId());
        if (allocResult.isLeft()) return Either.left(allocResult.getLeft());

        event.getSpendingItems().clear();
        if (event.getEventType() == EventType.SPENDING) {
            populateSpendingItems(event, request.getItems());
        }

        recalculateTotalAmount(event);
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
        List<EventProjectAllocationView> projViews = allocationRepository.findById_EventId(event.getId()).stream()
                .map(this::toProjectAllocationView)
                .toList();

        List<SpendingItemView> itemViews = spendingItemRepository.findByEvent_Id(event.getId()).stream()
                .map(this::toItemView)
                .toList();

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
                .spendingItems(itemViews)
                .build();
    }

    public SpendingEventPublishView toPublishView(FundingEventEntity event) {
        LocalDate date = event.getCreatedAt().toLocalDate();

        List<SpendingEventPublishView.ProjectAllocation> projAllocations =
                allocationRepository.findById_EventId(event.getId()).stream()
                        .map(this::toPublishProjectAllocation)
                        .toList();

        List<SpendingEventPublishView.SpendItem> items = spendingItemRepository.findByEvent_Id(event.getId()).stream()
                .map(this::toPublishItem)
                .toList();

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
                .items(items)
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

    private Either<ProblemDetail, Void> populateProjectAllocations(
            FundingEventEntity event,
            List<EventProjectAllocationRequest> allocationRequests,
            String organisationId) {

        for (EventProjectAllocationRequest req : allocationRequests) {
            Either<ProblemDetail, ProjectEntity> projectResult = resolveOrCreateProject(req, organisationId);
            if (projectResult.isLeft()) return Either.left(projectResult.getLeft());

            ProjectEntity project = projectResult.get();
            EventProjectAllocationEntity.Id allocId = new EventProjectAllocationEntity.Id(event.getId(), project.getId());
            EventProjectAllocationEntity allocation = EventProjectAllocationEntity.builder()
                    .id(allocId)
                    .event(event)
                    .project(project)
                    .build();

            Either<ProblemDetail, Void> milestoneResult = populateMilestoneAllocations(allocation, req.getMilestones(), project);
            if (milestoneResult.isLeft()) return Either.left(milestoneResult.getLeft());

            event.getProjectAllocations().add(allocation);
        }
        return Either.right(null);
    }

    private Either<ProblemDetail, Void> populateMilestoneAllocations(
            EventProjectAllocationEntity allocation,
            List<EventMilestoneAllocationRequest> milestoneRequests,
            ProjectEntity project) {

        for (EventMilestoneAllocationRequest req : milestoneRequests) {
            Either<ProblemDetail, MilestoneEntity> milestoneResult = resolveOrCreateMilestone(req.getMilestone(), project);
            if (milestoneResult.isLeft()) return Either.left(milestoneResult.getLeft());

            MilestoneEntity milestone = milestoneResult.get();
            EventMilestoneAllocationEntity.Id id = new EventMilestoneAllocationEntity.Id(
                    allocation.getId().getEventId(),
                    allocation.getId().getProjectUid(),
                    milestone.getId());

            allocation.getMilestoneAllocations().add(
                    EventMilestoneAllocationEntity.builder()
                            .id(id)
                            .allocatedAmount(req.getAllocatedAmount())
                            .allocation(allocation)
                            .milestone(milestone)
                            .build());
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
        if (req.getProjectId() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "projectId is required");
            problem.setTitle("PROJECT_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        // projectId is user-defined; derive SHA256 to look up or create
        String projectUid = ProjectEntity.id(organisationId, req.getProjectId());
        if (projectRepository.existsById(projectUid)) {
            return Either.right(projectRepository.findById(projectUid).orElseThrow());
        }

        // Project doesn't exist — create it
        if (req.getProjectTitle() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "projectTitle is required when creating a new project");
            problem.setTitle("PROJECT_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        // Root projects without a sub-project require amount + currency
        if (req.getSubProject() == null && (req.getTotalAmount() == null || req.getCurrency() == null)) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "totalAmount and currency are required when creating a new root project");
            problem.setTitle("PROJECT_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        String fundingId = req.getFundingId();
        if (fundingId == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "fundingId is required when creating a new project");
            problem.setTitle("PROJECT_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        ProjectEntity newProject = ProjectEntity.builder()
                .id(projectUid)
                .organisationId(organisationId)
                .fundingId(fundingId)
                .projectId(req.getProjectId())
                .projectTitle(req.getProjectTitle())
                .totalAmount(req.getTotalAmount())
                .currency(req.getCurrency())
                .build();
        return Either.right(projectRepository.saveAndFlush(newProject));
    }

    private Either<ProblemDetail, ProjectEntity> resolveOrCreateSubProject(SubProjectRequest subReq, ProjectEntity parent) {
        if (subReq.getSubProjectId() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "subProjectId is required for sub-project resolution");
            problem.setTitle("PROJECT_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        String subProjectUid = ProjectEntity.subId(parent.getId(), subReq.getSubProjectId());
        Optional<ProjectEntity> existing = projectRepository.findById(subProjectUid);
        if (existing.isPresent()) {
            return Either.right(existing.get());
        }

        if (subReq.getProjectTitle() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "projectTitle is required when creating a new sub-project");
            problem.setTitle("PROJECT_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        ProjectEntity subProject = ProjectEntity.builder()
                .id(subProjectUid)
                .organisationId(parent.getOrganisationId())
                .fundingId(parent.getFundingId())
                .projectId(subReq.getSubProjectId())
                .projectTitle(subReq.getProjectTitle())
                .parentProject(parent)
                .build();
        return Either.right(projectRepository.saveAndFlush(subProject));
    }

    private Either<ProblemDetail, MilestoneEntity> resolveOrCreateMilestone(MilestoneCreateRequest req, ProjectEntity project) {
        if (req.getMilestoneId() != null) {
            // Look up existing by (projectUid, user-defined milestoneId)
            Optional<MilestoneEntity> existing = milestoneRepository.findByProject_IdAndMilestoneId(project.getId(), req.getMilestoneId());
            if (existing.isPresent()) {
                return Either.right(existing.get());
            }
            // Not found — create it if creation fields are present; otherwise error
            if (req.getMilestoneTitle() == null || req.getMilestoneAmount() == null
                    || req.getCurrency() == null || req.getMilestoneDate() == null) {
                log.warn("Milestone not found: {} in project: {}", req.getMilestoneId(), project.getId());
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                        "Milestone not found: %s".formatted(req.getMilestoneId()));
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

        MilestoneEntity newMilestone = MilestoneEntity.builder()
                .id(UUID.randomUUID().toString())
                .milestoneId(req.getMilestoneId())
                .milestoneTitle(req.getMilestoneTitle())
                .milestoneAmount(req.getMilestoneAmount())
                .currency(req.getCurrency())
                .milestoneDate(req.getMilestoneDate())
                .project(project)
                .build();
        return Either.right(milestoneRepository.saveAndFlush(newMilestone));
    }

    private void populateSpendingItems(FundingEventEntity event, List<SpendingItemRequest> itemRequests) {
        itemRequests.stream()
                .map(req -> toSpendingItemEntity(req, event))
                .forEach(event.getSpendingItems()::add);
    }

    private void recalculateTotalAmount(FundingEventEntity event) {
        if (event.getEventType() == EventType.SPENDING) {
            BigDecimal total = event.getSpendingItems().stream()
                    .map(SpendingItemEntity::getAmountFcy)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            event.setTotalAmount(total);
        } else {
            BigDecimal total = event.getProjectAllocations().stream()
                    .flatMap(a -> a.getMilestoneAllocations().stream())
                    .filter(m -> m.getAllocatedAmount() != null)
                    .map(EventMilestoneAllocationEntity::getAllocatedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            event.setTotalAmount(total);
        }
    }

    private FundingEventEntity toEntity(SpendingEventCreateRequest request) {
        return FundingEventEntity.builder()
                .id(UUID.randomUUID().toString())
                .eventType(request.getEventType())
                .status(EventStatus.DRAFT)
                .organisationId(request.getOrganisationId())
                .fundingId(request.getFundingId())
                .fundingHash(request.getFundingHash())
                .fundingEntity(request.getFundingEntity())
                .currency(request.getCurrency())
                .build();
    }

    private SpendingItemEntity toSpendingItemEntity(SpendingItemRequest req, FundingEventEntity event) {
        return SpendingItemEntity.builder()
                .id(UUID.randomUUID().toString())
                .category(req.getCategory())
                .vendor(req.getVendor())
                .amountFcy(req.getAmountFcy())
                .currency(req.getCurrency())
                .fxRate(req.getFxRate())
                .amountRcy(req.getAmountRcy())
                .spendDate(req.getSpendDate())
                .hash(req.getHash())
                .notes(req.getNotes())
                .event(event)
                .build();
    }

    private EventProjectAllocationView toProjectAllocationView(EventProjectAllocationEntity alloc) {
        ProjectEntity project = alloc.getProject();
        List<EventMilestoneAllocationView> milestoneViews =
                milestoneAllocationRepository.findById_EventIdAndId_ProjectUid(
                        alloc.getId().getEventId(), alloc.getId().getProjectUid()).stream()
                        .map(this::toMilestoneAllocationView)
                        .toList();

        String parentProjectUid = (project != null && project.getParentProject() != null)
                ? project.getParentProject().getId() : null;

        return EventProjectAllocationView.builder()
                .eventId(alloc.getId().getEventId())
                .projectUid(alloc.getId().getProjectUid())
                .projectId(project != null ? project.getProjectId() : null)
                .projectTitle(project != null ? project.getProjectTitle() : null)
                .parentProjectUid(parentProjectUid)
                .milestoneAllocations(milestoneViews)
                .build();
    }

    private EventMilestoneAllocationView toMilestoneAllocationView(EventMilestoneAllocationEntity alloc) {
        MilestoneEntity milestone = milestoneRepository.findById(alloc.getId().getMilestoneUid()).orElse(null);
        return EventMilestoneAllocationView.builder()
                .eventId(alloc.getId().getEventId())
                .projectUid(alloc.getId().getProjectUid())
                .milestoneUid(alloc.getId().getMilestoneUid())
                .milestoneTitle(milestone != null ? milestone.getMilestoneTitle() : null)
                .milestoneAmount(milestone != null ? milestone.getMilestoneAmount() : null)
                .allocatedAmount(alloc.getAllocatedAmount())
                .currency(milestone != null ? milestone.getCurrency() : null)
                .milestoneDate(milestone != null ? milestone.getMilestoneDate() : null)
                .build();
    }

    private SpendingItemView toItemView(SpendingItemEntity item) {
        return SpendingItemView.builder()
                .itemId(item.getId())
                .eventId(item.getEvent().getId())
                .category(item.getCategory())
                .vendor(item.getVendor())
                .amountFcy(item.getAmountFcy())
                .currency(item.getCurrency())
                .fxRate(item.getFxRate())
                .amountRcy(item.getAmountRcy())
                .spendDate(item.getSpendDate())
                .hash(item.getHash())
                .notes(item.getNotes())
                .build();
    }

    private SpendingEventPublishView.ProjectAllocation toPublishProjectAllocation(EventProjectAllocationEntity alloc) {
        ProjectEntity project = alloc.getProject();

        List<SpendingEventPublishView.Milestone> milestones =
                milestoneAllocationRepository.findById_EventIdAndId_ProjectUid(
                        alloc.getId().getEventId(), alloc.getId().getProjectUid()).stream()
                        .map(this::toPublishMilestone)
                        .toList();

        String parentProjectUid = (project != null && project.getParentProject() != null)
                ? project.getParentProject().getId() : null;

        return SpendingEventPublishView.ProjectAllocation.builder()
                .projectUid(alloc.getId().getProjectUid())
                .projectId(project != null ? project.getProjectId() : null)
                .projectTitle(project != null ? project.getProjectTitle() : null)
                .parentProjectUid(parentProjectUid)
                .milestones(milestones)
                .build();
    }

    private SpendingEventPublishView.Milestone toPublishMilestone(EventMilestoneAllocationEntity alloc) {
        MilestoneEntity milestone = milestoneRepository.findById(alloc.getId().getMilestoneUid()).orElse(null);
        return SpendingEventPublishView.Milestone.builder()
                .milestoneUid(alloc.getId().getMilestoneUid())
                .milestoneTitle(milestone != null ? milestone.getMilestoneTitle() : null)
                .milestoneAmount(milestone != null ? milestone.getMilestoneAmount() : null)
                .allocatedAmount(alloc.getAllocatedAmount())
                .currency(milestone != null ? toCurrency(milestone.getCurrency()) : null)
                .milestoneDate(milestone != null ? milestone.getMilestoneDate() : null)
                .build();
    }

    private SpendingEventPublishView.SpendItem toPublishItem(SpendingItemEntity item) {
        return SpendingEventPublishView.SpendItem.builder()
                .itemId(item.getId())
                .category(item.getCategory())
                .vendor(item.getVendor())
                .amountFcy(item.getAmountFcy())
                .currency(toCurrency(item.getCurrency()))
                .fxRate(item.getFxRate())
                .amountRcy(item.getAmountRcy())
                .spendDate(item.getSpendDate())
                .documentHash(item.getHash())
                .notes(item.getNotes())
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
