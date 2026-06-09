package org.cardanofoundation.lob.app.funding.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.funding.domain.entity.*;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.EventMilestoneAllocationRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingEventCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingItemRequest;
import org.cardanofoundation.lob.app.funding.domain.view.EventMilestoneAllocationView;
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
    public Optional<SpendingEventEntity> create(String projectId, SpendingEventCreateRequest request) {
        return projectRepository.findById(projectId).map(project -> {
            SpendingEventEntity event = SpendingEventEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .projectId(project.getId())
                    .eventType(request.getEventType())
                    .status(EventStatus.DRAFT)
                    .fundingId(request.getFundingId())
                    .activityId(request.getActivityId())
                    .currency(request.getCurrency())
                    .fundingTx(request.getFundingTx())
                    .project(project)
                    .build();

            if (request.getEventType() == EventType.SPENDING) {
                populateSpendingItems(event, request.getSpendingItems());
                resolveSpendingMilestone(event, request.getMilestone(), project);
            } else {
                populateMilestoneAllocations(event, request.getMilestoneAllocations(), project);
            }

            recalculateTotalAmount(event);
            return spendingEventRepository.saveAndFlush(event);
        });
    }

    @Transactional
    public Optional<SpendingEventEntity> publish(String eventId) {
        return spendingEventRepository.findById(eventId).map(event -> {
            // TODO - Validation is needed
            event.setStatus(EventStatus.PUBLISHED);
            event.setLedgerDispatchApproved(true);
            return spendingEventRepository.saveAndFlush(event);
        });
    }

    @Transactional
    public boolean delete(String eventId) {
        return spendingEventRepository.findById(eventId).map(event -> {
            if (event.getStatus() == EventStatus.PUBLISHED) {
                return false;
            }
            spendingEventRepository.delete(event);
            return true;
        }).orElse(false);
    }

    private void populateSpendingItems(SpendingEventEntity event, List<SpendingItemRequest> itemRequests) {
        List<SpendingItemEntity> items = itemRequests.stream()
                .map(req -> SpendingItemEntity.builder()
                        .id(UUID.randomUUID().toString())
                        .eventId(event.getId())
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
                        .build())
                .toList();
        event.getSpendingItems().addAll(items);
    }

    private void resolveSpendingMilestone(SpendingEventEntity event, MilestoneCreateRequest milestoneRequest, ProjectEntity project) {
        if (milestoneRequest != null) {
            MilestoneEntity milestone = buildMilestone(milestoneRequest, project);
            milestoneRepository.saveAndFlush(milestone);
            event.setMilestoneId(milestone.getId());
        }
    }

    private void populateMilestoneAllocations(SpendingEventEntity event, List<EventMilestoneAllocationRequest> allocationRequests, ProjectEntity project) {
        List<EventMilestoneAllocationEntity> allocations = allocationRequests.stream()
                .map(req -> {
                    MilestoneEntity milestone = buildMilestone(req.getMilestone(), project);
                    milestoneRepository.saveAndFlush(milestone);
                    EventMilestoneAllocationEntity.Id id = new EventMilestoneAllocationEntity.Id(
                            event.getId(), milestone.getId());
                    return EventMilestoneAllocationEntity.builder()
                            .id(id)
                            .allocatedAmount(req.getAllocatedAmount())
                            .event(event)
                            .milestone(milestone)
                            .build();
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

    public SpendingEventView toView(SpendingEventEntity event) {
        List<SpendingItemView> itemViews = spendingItemRepository.findByEvent_Id(event.getId()).stream()
                .map(this::toItemView)
                .toList();

        List<EventMilestoneAllocationView> allocationViews = allocationRepository.findById_EventId(event.getId()).stream()
                .map(this::toAllocationView)
                .toList();

        return SpendingEventView.builder()
                .eventId(event.getId())
                .projectId(event.getProjectId())
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
                .spendingItems(itemViews)
                .milestoneAllocations(allocationViews)
                .build();
    }

    private SpendingItemView toItemView(SpendingItemEntity item) {
        return SpendingItemView.builder()
                .itemId(item.getId())
                .eventId(item.getEventId())
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
