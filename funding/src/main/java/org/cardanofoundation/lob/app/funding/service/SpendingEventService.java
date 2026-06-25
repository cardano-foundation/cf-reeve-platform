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
        event.setFundingTx(request.getFundingTx());
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
                .fundingTx(event.getFundingTx())
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
                .fundingTx(event.getFundingTx())
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
                    allocation.getId().getProjectId(),
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
        if (req.getProjectId() != null) {
            Optional<ProjectEntity> existing = projectRepository.findById(req.getProjectId());
            if (existing.isEmpty()) {
                log.warn("Project not found: {}", req.getProjectId());
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Project not found: %s".formatted(req.getProjectId()));
                problem.setTitle("PROJECT_NOT_FOUND");
                return Either.left(problem);
            }
            return Either.right(existing.get());
        }

        if (req.getActivityId() == null || req.getActivityTitle() == null
                || req.getExpectedTotalAmount() == null || req.getCurrency() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "activityId, activityTitle, expectedTotalAmount, currency are required when creating a new project");
            problem.setTitle("PROJECT_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        String fundingId = req.getFundingId();
        if (fundingId == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "fundingId is required when creating a new project");
            problem.setTitle("PROJECT_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        String projectId = ProjectEntity.id(organisationId, req.getActivityId());
        if (projectRepository.existsById(projectId)) {
            return Either.right(projectRepository.findById(projectId).orElseThrow());
        }

        ProjectEntity newProject = ProjectEntity.builder()
                .id(projectId)
                .organisationId(organisationId)
                .fundingId(fundingId)
                .activityId(req.getActivityId())
                .activityTitle(req.getActivityTitle())
                .activitySubId(req.getActivitySubId())
                .expectedTotalAmount(req.getExpectedTotalAmount())
                .currency(req.getCurrency())
                .build();
        return Either.right(projectRepository.saveAndFlush(newProject));
    }

    private Either<ProblemDetail, MilestoneEntity> resolveOrCreateMilestone(MilestoneCreateRequest req, ProjectEntity project) {
        if (req.getMilestoneId() != null) {
            Optional<MilestoneEntity> existing = milestoneRepository.findById(req.getMilestoneId());
            if (existing.isEmpty()) {
                log.warn("Milestone not found: {}", req.getMilestoneId());
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Milestone not found: %s".formatted(req.getMilestoneId()));
                problem.setTitle("MILESTONE_NOT_FOUND");
                return Either.left(problem);
            }
            return Either.right(existing.get());
        }

        if (req.getLabel() == null || req.getExpectedCost() == null
                || req.getCurrency() == null || req.getDueDate() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "label, expectedCost, currency, dueDate are required when creating a new milestone");
            problem.setTitle("MILESTONE_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        MilestoneEntity newMilestone = MilestoneEntity.builder()
                .id(UUID.randomUUID().toString())
                .label(req.getLabel())
                .expectedCost(req.getExpectedCost())
                .currency(req.getCurrency())
                .dueDate(req.getDueDate())
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
                .fundingTx(request.getFundingTx())
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
                milestoneAllocationRepository.findById_EventIdAndId_ProjectId(
                        alloc.getId().getEventId(), alloc.getId().getProjectId()).stream()
                        .map(this::toMilestoneAllocationView)
                        .toList();

        return EventProjectAllocationView.builder()
                .eventId(alloc.getId().getEventId())
                .projectId(alloc.getId().getProjectId())
                .activityId(project != null ? project.getActivityId() : null)
                .activityTitle(project != null ? project.getActivityTitle() : null)
                .activitySubId(project != null ? project.getActivitySubId() : null)
                .milestoneAllocations(milestoneViews)
                .build();
    }

    private EventMilestoneAllocationView toMilestoneAllocationView(EventMilestoneAllocationEntity alloc) {
        MilestoneEntity milestone = milestoneRepository.findById(alloc.getId().getMilestoneId()).orElse(null);
        return EventMilestoneAllocationView.builder()
                .eventId(alloc.getId().getEventId())
                .projectId(alloc.getId().getProjectId())
                .milestoneId(alloc.getId().getMilestoneId())
                .milestoneLabel(milestone != null ? milestone.getLabel() : null)
                .expectedCost(milestone != null ? milestone.getExpectedCost() : null)
                .allocatedAmount(alloc.getAllocatedAmount())
                .currency(milestone != null ? milestone.getCurrency() : null)
                .dueDate(milestone != null ? milestone.getDueDate() : null)
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
                milestoneAllocationRepository.findById_EventIdAndId_ProjectId(
                        alloc.getId().getEventId(), alloc.getId().getProjectId()).stream()
                        .map(this::toPublishMilestone)
                        .toList();

        return SpendingEventPublishView.ProjectAllocation.builder()
                .projectId(alloc.getId().getProjectId())
                .activityId(project != null ? project.getActivityId() : null)
                .activityTitle(project != null ? project.getActivityTitle() : null)
                .activitySubId(project != null ? project.getActivitySubId() : null)
                .milestones(milestones)
                .build();
    }

    private SpendingEventPublishView.Milestone toPublishMilestone(EventMilestoneAllocationEntity alloc) {
        MilestoneEntity milestone = milestoneRepository.findById(alloc.getId().getMilestoneId()).orElse(null);
        return SpendingEventPublishView.Milestone.builder()
                .milestoneId(alloc.getId().getMilestoneId())
                .milestoneLabel(milestone != null ? milestone.getLabel() : null)
                .expectedCost(milestone != null ? milestone.getExpectedCost() : null)
                .allocatedAmount(alloc.getAllocatedAmount())
                .currency(milestone != null ? toCurrency(milestone.getCurrency()) : null)
                .dueDate(milestone != null ? milestone.getDueDate() : null)
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
