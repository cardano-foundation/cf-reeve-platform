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
import org.hibernate.Hibernate;

import org.cardanofoundation.lob.app.funding.domain.entity.*;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.EventMilestoneAllocationRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingEventCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingItemRequest;
import org.cardanofoundation.lob.app.funding.domain.view.EventMilestoneAllocationView;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventPublishView;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingItemView;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;
import org.cardanofoundation.lob.app.funding.repository.SpendingEventRepository;
import org.cardanofoundation.lob.app.funding.repository.SpendingItemRepository;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpendingEventService {

    private final SpendingEventRepository spendingEventRepository;
    private final FundingProjectRepository projectRepository;
    private final MilestoneRepository milestoneRepository;
    private final SpendingItemRepository spendingItemRepository;
    private final EventMilestoneAllocationRepository allocationRepository;

    public Optional<SpendingEventEntity> findById(String eventId) {
        return spendingEventRepository.findById(eventId);
    }

    public List<SpendingEventEntity> findByProjectId(String projectId) {
        return spendingEventRepository.findByProject_Id(projectId);
    }

    public Page<SpendingEventEntity> findByProjectIdAndFilter(
            String projectId,
            Optional<EventStatus> status,
            Optional<EventType> eventType,
            Pageable pageable) {
        return spendingEventRepository.findByProjectIdAndFilter(
                projectId,
                status.orElse(null),
                eventType.orElse(null),
                pageable);
    }

    @Transactional
    public Either<ProblemDetail, SpendingEventEntity> create(String projectId, SpendingEventCreateRequest request) {
        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);

        if (projectM.isEmpty()) {
            log.warn("Project not found for id: {}", projectId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Project not found for id: %s".formatted(projectId));
            problem.setTitle("PROJECT_NOT_FOUND");
            return Either.left(problem);
        }

        ProjectEntity project = projectM.orElseThrow();
        SpendingEventEntity event = toEntity(project, request);

        if (request.getEventType() == EventType.SPENDING) {
            populateSpendingItems(event, request.getSpendingItems());
            Either<ProblemDetail, Void> milestoneResult = applySpendingMilestone(event, request.getMilestone(), project);
            if (milestoneResult.isLeft()) return Either.left(milestoneResult.getLeft());
        } else {
            Either<ProblemDetail, Void> allocResult = populateMilestoneAllocations(event, request.getMilestoneAllocations(), project);
            if (allocResult.isLeft()) return Either.left(allocResult.getLeft());
        }

        recalculateTotalAmount(event);
        return Either.right(spendingEventRepository.saveAndFlush(event));
    }

    @Transactional
    public Either<ProblemDetail, SpendingEventEntity> update(String projectId, String eventId, SpendingEventCreateRequest request) {
        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);

        if (projectM.isEmpty()) {
            log.warn("Project not found for id: {}", projectId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Project not found for id: %s".formatted(projectId));
            problem.setTitle("PROJECT_NOT_FOUND");
            return Either.left(problem);
        }

        Either<ProblemDetail, SpendingEventEntity> eventOrError = findEventOrError(eventId);
        if (eventOrError.isLeft()) return eventOrError;

        ProjectEntity project = projectM.orElseThrow();
        SpendingEventEntity event = eventOrError.get();

        event.setFundingId(request.getFundingId());
        event.setActivityId(request.getActivityId());
        event.setCurrency(request.getCurrency());
        event.setFundingTx(request.getFundingTx());

        if (event.getEventType() == EventType.SPENDING) {
            event.getSpendingItems().clear();
            populateSpendingItems(event, request.getSpendingItems());
            event.setMilestone(null);
            Either<ProblemDetail, Void> milestoneResult = applySpendingMilestone(event, request.getMilestone(), project);
            if (milestoneResult.isLeft()) return Either.left(milestoneResult.getLeft());
        } else {
            event.getMilestoneAllocations().clear();
            spendingEventRepository.flush();
            Either<ProblemDetail, Void> allocResult = populateMilestoneAllocations(event, request.getMilestoneAllocations(), project);
            if (allocResult.isLeft()) return Either.left(allocResult.getLeft());
        }

        recalculateTotalAmount(event);
        return Either.right(spendingEventRepository.saveAndFlush(event));
    }

    @Transactional
    public Either<ProblemDetail, SpendingEventEntity> publish(String eventId) {
        Either<ProblemDetail, SpendingEventEntity> eventOrError = findEventOrError(eventId);
        if (eventOrError.isLeft()) {
            return eventOrError;
        }

        SpendingEventEntity event = eventOrError.get();
        if (event.getStatus() == EventStatus.PUBLISHED) {
            log.warn("Event already published: {}", eventId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Event is already published: %s".formatted(eventId));
            problem.setTitle("SPENDING_EVENT_ALREADY_PUBLISHED");
            return Either.left(problem);
        }
        event.setStatus(EventStatus.PUBLISHED);
        event.setLedgerDispatchApproved(true);
        event = spendingEventRepository.saveAndFlush(event);
        // loading the entity to avoid lazyloading exception
        Hibernate.initialize(event.getMilestone());
        return Either.right(event);
    }

    @Transactional
    public Either<ProblemDetail, Void> delete(String eventId) {
        Either<ProblemDetail, SpendingEventEntity> eventOrError = findEventOrError(eventId);
        if (eventOrError.isLeft()) {
            return Either.left(eventOrError.getLeft());
        }

        SpendingEventEntity event = eventOrError.get();
        if (event.getStatus() == EventStatus.PUBLISHED) {
            log.warn("Cannot delete published event: {}", eventId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Cannot delete a published event: %s".formatted(eventId));
            problem.setTitle("SPENDING_EVENT_ALREADY_PUBLISHED");
            return Either.left(problem);
        }

        spendingEventRepository.delete(event);
        return Either.right(null);
    }

    private Either<ProblemDetail, SpendingEventEntity> findEventOrError(String eventId) {
        Optional<SpendingEventEntity> eventM = spendingEventRepository.findById(eventId);
        if (eventM.isEmpty()) {
            log.warn("Event not found for id: {}", eventId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Event not found for id: %s".formatted(eventId));
            problem.setTitle("SPENDING_EVENT_NOT_FOUND");
            return Either.left(problem);
        }
        return Either.right(eventM.orElseThrow());
    }

    private void populateSpendingItems(SpendingEventEntity event, List<SpendingItemRequest> itemRequests) {
        List<SpendingItemEntity> items = itemRequests.stream()
                .map(req -> toSpendingItemEntity(req, event))
                .toList();
        event.getSpendingItems().addAll(items);
    }

    /** Sets event.milestone for SPENDING events — finds existing by milestoneId or creates new from request fields. */
    private Either<ProblemDetail, Void> applySpendingMilestone(SpendingEventEntity event, MilestoneCreateRequest milestoneRequest, ProjectEntity project) {
        if (milestoneRequest == null) {
            return Either.right(null);
        }
        Either<ProblemDetail, MilestoneEntity> milestoneResult = resolveOrCreateMilestone(milestoneRequest, project);
        if (milestoneResult.isLeft()) return Either.left(milestoneResult.getLeft());
        event.setMilestone(milestoneResult.get());
        return Either.right(null);
    }

    private Either<ProblemDetail, Void> populateMilestoneAllocations(SpendingEventEntity event, List<EventMilestoneAllocationRequest> allocationRequests, ProjectEntity project) {
        for (EventMilestoneAllocationRequest req : allocationRequests) {
            Either<ProblemDetail, MilestoneEntity> milestoneResult = resolveOrCreateMilestone(req.getMilestone(), project);
            if (milestoneResult.isLeft()) return Either.left(milestoneResult.getLeft());
            MilestoneEntity milestone = milestoneResult.get();
            EventMilestoneAllocationEntity.Id id = new EventMilestoneAllocationEntity.Id(event.getId(), milestone.getId());
            event.getMilestoneAllocations().add(toAllocationEntity(id, req.getAllocatedAmount(), event, milestone));
        }
        return Either.right(null);
    }

    /** Used by SPENDING events: finds existing milestone by milestoneId or creates a new one from MilestoneCreateRequest. */
    private Either<ProblemDetail, MilestoneEntity> resolveOrCreateMilestone(MilestoneCreateRequest req, ProjectEntity project) {
        if (req.getMilestoneId() != null) {
            Optional<MilestoneEntity> existing = milestoneRepository.findById(req.getMilestoneId());
            if (existing.isEmpty()) {
                log.warn("Milestone not found for id: {}", req.getMilestoneId());
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Milestone not found for id: %s".formatted(req.getMilestoneId()));
                problem.setTitle("MILESTONE_NOT_FOUND");
                return Either.left(problem);
            }
            return Either.right(existing.get());
        }

        if (req.getLabel() == null || req.getExpectedCost() == null
                || req.getCurrency() == null || req.getDueDate() == null) {
            log.warn("Missing required fields for milestone creation");
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

    private void recalculateTotalAmount(SpendingEventEntity event) {
        if (event.getEventType() == EventType.SPENDING) {
            BigDecimal total = event.getSpendingItems().stream()
                    .map(SpendingItemEntity::getAmountFcy)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            event.setTotalAmount(total);
        } else {
            BigDecimal total = event.getMilestoneAllocations().stream()
                    .filter(a -> a.getAllocatedAmount() != null)
                    .map(EventMilestoneAllocationEntity::getAllocatedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            event.setTotalAmount(total);
        }
    }

    private EventMilestoneAllocationEntity toAllocationEntity(EventMilestoneAllocationEntity.Id id, BigDecimal allocatedAmount, SpendingEventEntity event, MilestoneEntity milestone) {
        return EventMilestoneAllocationEntity.builder()
                .id(id)
                .allocatedAmount(allocatedAmount)
                .event(event)
                .milestone(milestone)
                .build();
    }

    private SpendingEventEntity toEntity(ProjectEntity project, SpendingEventCreateRequest request) {
        return SpendingEventEntity.builder()
                .id(UUID.randomUUID().toString())
                .eventType(request.getEventType())
                .status(EventStatus.DRAFT)
                .fundingId(request.getFundingId())
                .activityId(request.getActivityId())
                .currency(request.getCurrency())
                .fundingTx(request.getFundingTx())
                .project(project)
                .build();
    }

    public SpendingEventView toView(SpendingEventEntity event) {
        List<SpendingItemView> itemViews = spendingItemRepository.findByEvent_Id(event.getId()).stream()
                .map(this::toItemView)
                .toList();

        List<EventMilestoneAllocationView> allocationViews = allocationRepository.findById_EventId(event.getId()).stream()
                .map(this::toAllocationView)
                .toList();

        MilestoneEntity milestone = Optional.ofNullable(event.getMilestone())
                .map(MilestoneEntity::getId)
                .flatMap(milestoneRepository::findById)
                .orElse(null);

        return SpendingEventView.builder()
                .eventId(event.getId())
                .projectId(event.getProject().getId())
                .eventType(event.getEventType())
                .status(event.getStatus())
                .fundingId(event.getFundingId())
                .activityId(event.getActivityId())
                .totalAmount(event.getTotalAmount())
                .currency(event.getCurrency())
                .txHash(event.getTxHash())
                .ledgerDispatchStatus(event.getLedgerDispatchStatus())
                .fundingTx(event.getFundingTx())
                .milestoneId(milestone != null ? milestone.getId() : null)
                .milestoneLabel(milestone != null ? milestone.getLabel() : null)
                .spendingItems(itemViews)
                .milestoneAllocations(allocationViews)
                .build();
    }

    private SpendingItemEntity toSpendingItemEntity(SpendingItemRequest req, SpendingEventEntity event) {
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

    private EventMilestoneAllocationView toAllocationView(EventMilestoneAllocationEntity allocation) {
        MilestoneEntity milestone = milestoneRepository.findById(allocation.getId().getMilestoneId()).orElse(null);
        return EventMilestoneAllocationView.builder()
                .eventId(allocation.getId().getEventId())
                .milestoneId(allocation.getId().getMilestoneId())
                .milestoneLabel(milestone != null ? milestone.getLabel() : null)
                .expectedCost(milestone != null ? milestone.getExpectedCost() : null)
                .allocatedAmount(allocation.getAllocatedAmount())
                .currency(milestone != null ? milestone.getCurrency() : null)
                .dueDate(milestone != null ? milestone.getDueDate() : null)
                .build();
    }

    /**
     * Builds the {@link SpendingEventPublishView} (blockchain-publisher contract) for a publishable event,
     * resolving the funding context, milestones and spend items needed for the {@code EVENT_BUNDLE} metadata.
     */
    public SpendingEventPublishView toPublishView(SpendingEventEntity event) {
        ProjectEntity project = event.getProject();

        LocalDate date = event.getCreatedAt() != null
                ? event.getCreatedAt().toLocalDate()
                : (event.getPublishedAt() != null ? event.getPublishedAt().toLocalDate() : null);

        List<SpendingEventPublishView.SpendItem> items = spendingItemRepository.findByEvent_Id(event.getId()).stream()
                .map(this::toPublishItem)
                .toList();

        // SPENDING events target a single milestone (event.milestone); FUNDING/REFUND carry milestone allocations.
        List<SpendingEventPublishView.Milestone> milestones = event.getEventType() == EventType.SPENDING
                ? spendingMilestone(event)
                : allocationRepository.findById_EventId(event.getId()).stream()
                        .map(this::toPublishMilestone)
                        .toList();

        return SpendingEventPublishView.builder()
                .eventId(event.getId())
                .projectId(event.getProject().getId())
                .eventType(event.getEventType())
                .date(date)
                .fundingId(event.getFundingId())
                .activityId(event.getActivityId())
                .activityTitle(project != null ? project.getActivityTitle() : null)
                .fundingTx(event.getFundingTx())
                .fundingDocHash(null)
                .amount(event.getTotalAmount())
                .currency(toCurrency(event.getCurrency()))
                .milestones(milestones)
                .items(items)
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

    /** The single targeted milestone of a SPENDING event (no allocated amount - that is a FUNDING/REFUND concept). */
    private List<SpendingEventPublishView.Milestone> spendingMilestone(SpendingEventEntity event) {
        // event may be detached; reading the proxy's id is safe, the rest is loaded via the repository.
        String milestoneId = event.getMilestone() != null ? event.getMilestone().getId() : null;
        if (milestoneId == null) {
            return List.of();
        }
        return milestoneRepository.findById(milestoneId)
                .map(milestone -> List.of(SpendingEventPublishView.Milestone.builder()
                        .milestoneId(milestone.getId())
                        .milestoneLabel(milestone.getLabel())
                        .expectedCost(milestone.getExpectedCost())
                        .allocatedAmount(null)
                        .currency(toCurrency(milestone.getCurrency()))
                        .dueDate(milestone.getDueDate())
                        .build()))
                .orElseGet(List::of);
    }

    private SpendingEventPublishView.Milestone toPublishMilestone(EventMilestoneAllocationEntity allocation) {
        MilestoneEntity milestone = milestoneRepository.findById(allocation.getId().getMilestoneId()).orElse(null);
        return SpendingEventPublishView.Milestone.builder()
                .milestoneId(allocation.getId().getMilestoneId())
                .milestoneLabel(milestone != null ? milestone.getLabel() : null)
                .expectedCost(milestone != null ? milestone.getExpectedCost() : null)
                .allocatedAmount(allocation.getAllocatedAmount())
                .currency(milestone != null ? toCurrency(milestone.getCurrency()) : null)
                .dueDate(milestone != null ? milestone.getDueDate() : null)
                .build();
    }

    /**
     * TODO Needs to add here the connection to the org module.
     */
    private static SpendingEventPublishView.Currency toCurrency(String currencyCode) {
        if (currencyCode == null) {
            return null;
        }
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
