package org.cardanofoundation.lob.app.funding.service;

import java.math.BigDecimal;
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
import org.cardanofoundation.lob.app.funding.domain.request.EventMilestoneAllocationRequest;
import org.cardanofoundation.lob.app.funding.domain.request.FundingEventCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.FundingItemRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.EventMilestoneAllocationView;
import org.cardanofoundation.lob.app.funding.domain.view.FundingEventView;
import org.cardanofoundation.lob.app.funding.domain.view.FundingItemView;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingEventRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingItemRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FundingEventService {

    private final FundingEventRepository fundingEventRepository;
    private final FundingProjectRepository projectRepository;
    private final MilestoneRepository milestoneRepository;
    private final FundingItemRepository fundingItemRepository;
    private final EventMilestoneAllocationRepository allocationRepository;

    public Optional<FundingEventEntity> findById(String eventId) {
        return fundingEventRepository.findById(eventId);
    }

    public List<FundingEventEntity> findByProjectId(String projectId) {
        return fundingEventRepository.findByProject_Id(projectId);
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
    public Either<ProblemDetail, FundingEventEntity> create(String projectId, FundingEventCreateRequest request) {
        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);

        if (projectM.isEmpty()) {
            log.warn("Project not found for id: {}", projectId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Project not found for id: %s".formatted(projectId));
            problem.setTitle("PROJECT_NOT_FOUND");
            return Either.left(problem);
        }

        ProjectEntity project = projectM.orElseThrow();
        FundingEventEntity event = toEntity(project, request);

        if (request.getEventType() == EventType.SPENDING) {
            populateFundingItems(event, request.getFundingItems());
            resolveFundingMilestone(event, request.getMilestone(), project);
        } else {
            populateMilestoneAllocations(event, request.getMilestoneAllocations(), project);
        }

        recalculateTotalAmount(event);
        return Either.right(fundingEventRepository.saveAndFlush(event));
    }

    @Transactional
    public Either<ProblemDetail, FundingEventEntity> publish(String eventId) {
        Optional<FundingEventEntity> eventM = fundingEventRepository.findById(eventId);

        if (eventM.isEmpty()) {
            log.warn("Event not found for id: {}", eventId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Event not found for id: %s".formatted(eventId));
            problem.setTitle("FUNDING_EVENT_NOT_FOUND");
            return Either.left(problem);
        }

        FundingEventEntity event = eventM.orElseThrow();
        if (event.getStatus() == EventStatus.PUBLISHED) {
            log.warn("Event already published: {}", eventId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Event is already published: %s".formatted(eventId));
            problem.setTitle("FUNDING_EVENT_ALREADY_PUBLISHED");
            return Either.left(problem);
        }
        event.setStatus(EventStatus.PUBLISHED);
        event.setLedgerDispatchApproved(true);
        return Either.right(fundingEventRepository.saveAndFlush(event));
    }

    @Transactional
    public Either<ProblemDetail, Void> delete(String eventId) {
        Optional<FundingEventEntity> eventM = fundingEventRepository.findById(eventId);

        if (eventM.isEmpty()) {
            log.warn("Event not found for id: {}", eventId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Event not found for id: %s".formatted(eventId));
            problem.setTitle("FUNDING_EVENT_NOT_FOUND");
            return Either.left(problem);
        }

        FundingEventEntity event = eventM.orElseThrow();
        if (event.getStatus() == EventStatus.PUBLISHED) {
            log.warn("Cannot delete published event: {}", eventId);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Cannot delete a published event: %s".formatted(eventId));
            problem.setTitle("FUNDING_EVENT_ALREADY_PUBLISHED");
            return Either.left(problem);
        }

        fundingEventRepository.delete(event);
        return Either.right(null);
    }

    private void populateFundingItems(FundingEventEntity event, List<FundingItemRequest> itemRequests) {
        List<FundingItemEntity> items = itemRequests.stream()
                .map(req -> toFundingItemEntity(req, event))
                .toList();
        event.getFundingItems().addAll(items);
    }

    private void resolveFundingMilestone(FundingEventEntity event, MilestoneCreateRequest milestoneRequest, ProjectEntity project) {
        if (milestoneRequest != null) {
            MilestoneEntity milestone = buildMilestone(milestoneRequest, project);
            milestoneRepository.saveAndFlush(milestone);
            event.setMilestoneId(milestone.getId());
        }
    }

    private void populateMilestoneAllocations(FundingEventEntity event, List<EventMilestoneAllocationRequest> allocationRequests, ProjectEntity project) {
        List<EventMilestoneAllocationEntity> allocations = allocationRequests.stream()
                .map(req -> {
                    MilestoneEntity milestone = buildMilestone(req.getMilestone(), project);
                    milestoneRepository.saveAndFlush(milestone);
                    EventMilestoneAllocationEntity.Id id = new EventMilestoneAllocationEntity.Id(
                            event.getId(), milestone.getId());
                    return toAllocationEntity(id, req, event, milestone);
                })
                .toList();
        event.getMilestoneAllocations().addAll(allocations);
    }

    private MilestoneEntity buildMilestone(MilestoneCreateRequest req, ProjectEntity project) {
        return MilestoneEntity.builder()
                .id(UUID.randomUUID().toString())
                .label(req.getLabel())
                .expectedCost(req.getExpectedCost())
                .currency(req.getCurrency())
                .dueDate(req.getDueDate())
                .project(project)
                .build();
    }

    private void recalculateTotalAmount(FundingEventEntity event) {
        if (event.getEventType() == EventType.SPENDING) {
            BigDecimal total = event.getFundingItems().stream()
                    .map(FundingItemEntity::getAmountFcy)
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

    private EventMilestoneAllocationEntity toAllocationEntity(EventMilestoneAllocationEntity.Id id, EventMilestoneAllocationRequest req, FundingEventEntity event, MilestoneEntity milestone) {
        return EventMilestoneAllocationEntity.builder()
                .id(id)
                .allocatedAmount(req.getAllocatedAmount())
                .event(event)
                .milestone(milestone)
                .build();
    }

    private FundingEventEntity toEntity(ProjectEntity project, FundingEventCreateRequest request) {
        return FundingEventEntity.builder()
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

    public FundingEventView toView(FundingEventEntity event) {
        List<FundingItemView> itemViews = fundingItemRepository.findByEvent_Id(event.getId()).stream()
                .map(this::toItemView)
                .toList();

        List<EventMilestoneAllocationView> allocationViews = allocationRepository.findById_EventId(event.getId()).stream()
                .map(this::toAllocationView)
                .toList();

        return FundingEventView.builder()
                .eventId(event.getId())
                .projectId(event.getProject().getId())
                .eventType(event.getEventType())
                .status(event.getStatus())
                .fundingId(event.getFundingId())
                .activityId(event.getActivityId())
                .totalAmount(event.getTotalAmount())
                .currency(event.getCurrency())
                .txHash(event.getTxHash())
                .fundingTx(event.getFundingTx())
                .milestoneId(event.getMilestoneId())
                .milestoneLabel(event.getMilestoneId() != null
                        ? milestoneRepository.findById(event.getMilestoneId()).map(MilestoneEntity::getLabel).orElse(null)
                        : null)
                .fundingItems(itemViews)
                .milestoneAllocations(allocationViews)
                .build();
    }

    private FundingItemEntity toFundingItemEntity(FundingItemRequest req, FundingEventEntity event) {
        return FundingItemEntity.builder()
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

    private FundingItemView toItemView(FundingItemEntity item) {
        return FundingItemView.builder()
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

}
