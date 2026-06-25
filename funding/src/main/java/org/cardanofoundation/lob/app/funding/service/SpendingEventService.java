package org.cardanofoundation.lob.app.funding.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

        Either<ProblemDetail, Void> allocResult = populateMilestoneAllocations(event, request.getAllocations(), request.getOrganisationId());
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

        event.getMilestoneAllocations().clear();
        fundingEventRepository.flush();

        Either<ProblemDetail, Void> allocResult = populateMilestoneAllocations(event, request.getAllocations(), request.getOrganisationId());
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
        List<EventProjectAllocationView> projViews = buildProjectAllocationViews(event.getId());

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

        List<SpendingEventPublishView.ProjectAllocation> projAllocations = buildPublishProjectAllocations(event.getId());

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

            for (EventMilestoneAllocationRequest milestoneReq : req.getMilestones()) {
                Either<ProblemDetail, MilestoneEntity> milestoneResult = resolveOrCreateMilestone(milestoneReq.getMilestone(), project);
                if (milestoneResult.isLeft()) return Either.left(milestoneResult.getLeft());

                MilestoneEntity milestone = milestoneResult.get();
                EventMilestoneAllocationEntity.Id id = new EventMilestoneAllocationEntity.Id(event.getId(), milestone.getId());

                event.getMilestoneAllocations().add(
                        EventMilestoneAllocationEntity.builder()
                                .id(id)
                                .allocatedAmount(milestoneReq.getAllocatedAmount())
                                .build());
            }
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
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "projectId is required");
            problem.setTitle("PROJECT_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        String projectUid = ProjectEntity.id(organisationId, req.getProjectId());
        if (projectRepository.existsById(projectUid)) {
            return Either.right(projectRepository.findById(projectUid).orElseThrow());
        }

        if (req.getProjectTitle() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "projectTitle is required when creating a new project");
            problem.setTitle("PROJECT_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        if (req.getSubProject() == null && (req.getTotalAmount() == null || req.getCurrency() == null)) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "totalAmount and currency are required when creating a new root project");
            problem.setTitle("PROJECT_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        if (req.getFundingId() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "fundingId is required when creating a new project");
            problem.setTitle("PROJECT_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        ProjectEntity newProject = ProjectEntity.builder()
                .id(projectUid)
                .organisationId(organisationId)
                .fundingId(req.getFundingId())
                .projectId(req.getProjectId())
                .projectTitle(req.getProjectTitle())
                .totalAmount(req.getTotalAmount())
                .currency(req.getCurrency())
                .build();
        return Either.right(projectRepository.saveAndFlush(newProject));
    }

    private Either<ProblemDetail, ProjectEntity> resolveOrCreateSubProject(SubProjectRequest subReq, ProjectEntity parent) {
        if (subReq.getSubProjectId() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "subProjectId is required for sub-project resolution");
            problem.setTitle("PROJECT_FIELDS_REQUIRED");
            return Either.left(problem);
        }

        String subProjectUid = ProjectEntity.subId(parent.getId(), subReq.getSubProjectId());
        Optional<ProjectEntity> existing = projectRepository.findById(subProjectUid);
        if (existing.isPresent()) {
            return Either.right(existing.get());
        }

        if (subReq.getProjectTitle() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "projectTitle is required when creating a new sub-project");
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
            Optional<MilestoneEntity> existing = milestoneRepository.findByProject_IdAndMilestoneId(project.getId(), req.getMilestoneId());
            if (existing.isPresent()) {
                return Either.right(existing.get());
            }
            if (req.getMilestoneTitle() == null || req.getMilestoneAmount() == null
                    || req.getCurrency() == null || req.getMilestoneDate() == null) {
                log.warn("Milestone not found: {} in project: {}", req.getMilestoneId(), project.getId());
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Milestone not found: %s".formatted(req.getMilestoneId()));
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
            BigDecimal total = event.getMilestoneAllocations().stream()
                    .filter(m -> m.getAllocatedAmount() != null)
                    .map(EventMilestoneAllocationEntity::getAllocatedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            event.setTotalAmount(total);
        }
    }

    /** Groups flat milestone allocations by their project, preserving insertion order. */
    private Map<ProjectEntity, List<EventMilestoneAllocationEntity>> groupByProject(String eventId) {
        return milestoneAllocationRepository.findById_EventId(eventId).stream()
                .filter(a -> {
                    MilestoneEntity m = milestoneRepository.findById(a.getId().getMilestoneUid()).orElse(null);
                    return m != null && m.getProject() != null;
                })
                .collect(Collectors.groupingBy(
                        a -> milestoneRepository.findById(a.getId().getMilestoneUid()).orElseThrow().getProject(),
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
                            .projectUid(project.getId())
                            .projectId(project.getProjectId())
                            .projectTitle(project.getProjectTitle())
                            .parentProjectUid(project.getParentProject() != null ? project.getParentProject().getId() : null)
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
                    return SpendingEventPublishView.ProjectAllocation.builder()
                            .projectUid(project.getId())
                            .projectId(project.getProjectId())
                            .projectTitle(project.getProjectTitle())
                            .parentProjectUid(project.getParentProject() != null ? project.getParentProject().getId() : null)
                            .milestones(milestones)
                            .build();
                })
                .toList();
    }

    private EventMilestoneAllocationView toMilestoneAllocationView(EventMilestoneAllocationEntity alloc) {
        MilestoneEntity milestone = milestoneRepository.findById(alloc.getId().getMilestoneUid()).orElse(null);
        return EventMilestoneAllocationView.builder()
                .eventId(alloc.getId().getEventId())
                .milestoneUid(alloc.getId().getMilestoneUid())
                .milestoneId(milestone != null ? milestone.getMilestoneId() : null)
                .milestoneTitle(milestone != null ? milestone.getMilestoneTitle() : null)
                .milestoneAmount(milestone != null ? milestone.getMilestoneAmount() : null)
                .allocatedAmount(alloc.getAllocatedAmount())
                .currency(milestone != null ? milestone.getCurrency() : null)
                .milestoneDate(milestone != null ? milestone.getMilestoneDate() : null)
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
