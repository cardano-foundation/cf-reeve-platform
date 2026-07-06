package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.funding.domain.entity.*;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.*;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventPublishView;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.repository.*;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class SpendingEventServiceTest {

    @Mock
    private FundingEventRepository fundingEventRepository;
    @Mock
    private FundingProjectRepository projectRepository;
    @Mock
    private MilestoneRepository milestoneRepository;
    @Mock
    private EventMilestoneAllocationRepository milestoneAllocationRepository;
    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @InjectMocks
    private SpendingEventService spendingEventService;

    private static final Pageable PAGEABLE = PageRequest.of(0, 10);
    private static final LocalDate FUTURE_DATE = LocalDate.now().plusYears(1);

    // Consistent spend numbers: amountFcy = amountRcy * fxRate  (100000 = 50000 * 2)
    private static final BigDecimal ALLOCATED = new BigDecimal("50000.00");
    private static final BigDecimal AMOUNT_RCY = new BigDecimal("50000.00");
    private static final BigDecimal AMOUNT_FCY = new BigDecimal("100000.00");
    private static final BigDecimal FX_RATE = new BigDecimal("2");

    // --- findById ---

    @Test
    void findById_delegatesToRepository() {
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(event));

        assertThat(spendingEventService.findById("e1")).contains(event);
    }

    @Test
    void findById_returnsEmpty_whenNotFound() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.empty());

        assertThat(spendingEventService.findById("e1")).isEmpty();
    }

    // --- filters ---

    @Test
    void findByOrganisationIdAndFilter_delegatesToRepository() {
        var page = new PageImpl<>(List.of(eventEntity(EventType.SPENDING, EventStatus.DRAFT)));
        when(fundingEventRepository.findByOrganisationIdAndFilter("org1", EventStatus.DRAFT, EventType.SPENDING, PAGEABLE))
                .thenReturn(page);

        assertThat(spendingEventService.findByOrganisationIdAndFilter(
                "org1", Optional.of(EventStatus.DRAFT), Optional.of(EventType.SPENDING), PAGEABLE)).isEqualTo(page);
    }

    @Test
    void findByProjectIdAndFilter_passesNulls_whenFiltersEmpty() {
        var page = new PageImpl<FundingEventEntity>(List.of());
        when(fundingEventRepository.findByProjectIdAndFilter("p1", null, null, PAGEABLE)).thenReturn(page);

        assertThat(spendingEventService.findByProjectIdAndFilter("p1", Optional.empty(), Optional.empty(), PAGEABLE))
                .isEqualTo(page);
    }

    // --- create: success ---

    @Test
    void create_fundingEvent_success_totalIsSumOfAllocations() {
        stubExistingProjectAndMilestone("MS-1");
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(
                fundingRequest(fundingMilestone("MS-1", ALLOCATED)));

        assertThat(result.isRight()).isTrue();
        verify(fundingEventRepository).saveAndFlush(argThat(e -> e.getTotalAmount().compareTo(ALLOCATED) == 0));
    }

    @Test
    void create_spendingEvent_success_setsSpendDetailOnAllocation() {
        stubExistingProjectAndMilestone("MS-1");
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(
                spendingRequest(spendingMilestone("MS-1")));

        assertThat(result.isRight()).isTrue();
        verify(fundingEventRepository).saveAndFlush(argThat(e -> {
            EventMilestoneAllocationEntity alloc = e.getMilestoneAllocations().get(0);
            return e.getTotalAmount().compareTo(ALLOCATED) == 0
                    && alloc.getAmountFcy().compareTo(AMOUNT_FCY) == 0
                    && alloc.getAmountRcy().compareTo(AMOUNT_RCY) == 0
                    && "Vendor AB".equals(alloc.getVendor());
        }));
    }

    @Test
    void create_successWithNewProjectAndNewMilestone() {
        when(projectRepository.existsById(any())).thenReturn(false);
        when(projectRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(milestoneRepository.saveAndFlush(any())).thenAnswer(i -> milestoneEntity("m-new"));
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SpendingEventCreateRequest request = fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-NEW").projectTitle("New Project").fundingId("GRANT-2025-001")
                .totalAmount(new BigDecimal("100000.00")).currency("USD")
                .milestones(List.of(EventMilestoneAllocationRequest.builder()
                        .milestone(MilestoneCreateRequest.builder().milestoneTitle("New MS")
                                .milestoneAmount(new BigDecimal("60000.00")).currency("USD").milestoneDate(FUTURE_DATE).build())
                        .allocatedAmount(ALLOCATED).build()))
                .build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isRight()).isTrue();
        verify(milestoneRepository).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenEventAlreadyExists() {
        when(fundingEventRepository.existsById(any())).thenReturn(true);

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(
                fundingRequest(fundingMilestone("MS-1", ALLOCATED)));

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_EXISTS);
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    // --- create: allocation validations ---

    @Test
    void create_returnsLeft_whenAllocatedAmountMissing() {
        stubExistingProjectAndMilestone("MS-1");

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(
                fundingRequest(fundingMilestone("MS-1", null)));

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.ALLOCATION_AMOUNT_REQUIRED);
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenAllocatedAmountExceedsMilestone() {
        stubExistingProjectAndMilestone("MS-1"); // milestone amount 50000

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(
                fundingRequest(fundingMilestone("MS-1", new BigDecimal("60000.00"))));

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.ALLOCATION_EXCEEDS_MILESTONE);
    }

    @Test
    void create_returnsLeft_whenAllocationTotalExceedsProject() {
        ProjectEntity project = projectEntity(); // total 200000
        MilestoneEntity m1 = milestoneEntityWithAmount("m1", new BigDecimal("150000.00"));
        MilestoneEntity m2 = milestoneEntityWithAmount("m2", new BigDecimal("150000.00"));
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(project));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(project.getId(), "MS-1")).thenReturn(Optional.of(m1));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(project.getId(), "MS-2")).thenReturn(Optional.of(m2));

        SpendingEventCreateRequest request = fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-AB")
                .milestones(List.of(fundingMilestone("MS-1", new BigDecimal("150000.00")),
                                    fundingMilestone("MS-2", new BigDecimal("150000.00"))))
                .build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.ALLOCATION_TOTAL_EXCEEDS_PROJECT);
    }

    // --- create: spend-detail validations ---

    @Test
    void create_returnsLeft_whenSpendFieldsOnNonSpendingEvent() {
        stubExistingProjectAndMilestone("MS-1");

        // FUNDING event with spend fields present
        EventMilestoneAllocationRequest ms = fundingMilestone("MS-1", ALLOCATED);
        ms.setAmountFcy(AMOUNT_FCY);
        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(fundingRequest(ms));

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.SPEND_FIELDS_NOT_ALLOWED);
    }

    @Test
    void create_returnsLeft_whenSpendingEventMissingSpendFields() {
        stubExistingProjectAndMilestone("MS-1");

        // SPENDING event but no amountFcy/amountRcy/fxRate/spendDate
        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(
                spendingRequest(fundingMilestone("MS-1", ALLOCATED)));

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.SPEND_FIELDS_REQUIRED);
    }

    @Test
    void create_returnsLeft_whenFxRateDoesNotMatch() {
        stubExistingProjectAndMilestone("MS-1");

        EventMilestoneAllocationRequest ms = spendingMilestone("MS-1");
        ms.setFxRate(new BigDecimal("3")); // 50000 * 3 = 150000 != amountFcy 100000
        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(spendingRequest(ms));

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.FX_RATE_MISMATCH);
    }

    @Test
    void create_returnsLeft_whenAllocatedExceedsAmountRcy() {
        stubExistingProjectAndMilestone("MS-1");

        EventMilestoneAllocationRequest ms = spendingMilestone("MS-1");
        ms.setAllocatedAmount(new BigDecimal("50001.00")); // > amountRcy 50000 (still <= milestone 50000? no)
        ms.setAmountRcy(new BigDecimal("50000.00"));
        // keep allocated <= milestone by bumping milestone check: milestone amount is 50000, so use 50000.50
        ms.setAllocatedAmount(new BigDecimal("50000.00")); // == milestone; make amountRcy smaller to trip the spend check
        ms.setAmountRcy(new BigDecimal("40000.00"));
        ms.setFxRate(new BigDecimal("2.5")); // 40000 * 2.5 = 100000 = amountFcy
        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(spendingRequest(ms));

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.ALLOCATION_EXCEEDS_SPEND);
    }

    @Test
    void create_returnsLeft_whenEventHasNoAllocations() {
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(projectEntity()));

        SpendingEventCreateRequest request = fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-AB").milestones(List.of()).build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.EVENT_AMOUNT_INVALID);
    }

    // --- create: project/milestone structural validations ---

    @Test
    void create_returnsLeft_whenNewProjectAmountNotPositive() {
        when(projectRepository.existsById(any())).thenReturn(false);

        SpendingEventCreateRequest request = fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-NEW").projectTitle("New").fundingId("GRANT-2025-001")
                .totalAmount(BigDecimal.ZERO).currency("USD")
                .milestones(List.of(EventMilestoneAllocationRequest.builder()
                        .milestone(MilestoneCreateRequest.builder().externalMilestoneId("MS-1").build())
                        .allocatedAmount(ALLOCATED).build()))
                .build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_AMOUNT_INVALID);
    }

    @Test
    void create_returnsLeft_whenNewMilestoneDateInPast() {
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(projectEntity()));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(any(), eq("MS-NEW"))).thenReturn(Optional.empty());

        SpendingEventCreateRequest request = fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-AB")
                .milestones(List.of(EventMilestoneAllocationRequest.builder()
                        .milestone(MilestoneCreateRequest.builder().externalMilestoneId("MS-NEW").milestoneTitle("MS")
                                .milestoneAmount(new BigDecimal("50000.00")).currency("USD")
                                .milestoneDate(LocalDate.now().minusDays(1)).build())
                        .allocatedAmount(ALLOCATED).build()))
                .build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_DATE_IN_PAST);
    }

    @Test
    void create_returnsLeft_whenAddingMilestoneToProjectWithSubProjects() {
        ProjectEntity project = projectEntity();
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(project));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(project.getId(), "MS-NEW")).thenReturn(Optional.empty());
        when(projectRepository.existsByParentProjectId(project.getId())).thenReturn(true);

        SpendingEventCreateRequest request = fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-AB")
                .milestones(List.of(EventMilestoneAllocationRequest.builder()
                        .milestone(MilestoneCreateRequest.builder().externalMilestoneId("MS-NEW").milestoneTitle("MS")
                                .milestoneAmount(new BigDecimal("50000.00")).currency("USD").milestoneDate(FUTURE_DATE).build())
                        .allocatedAmount(ALLOCATED).build()))
                .build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_NOT_ALLOWED_WITH_SUBPROJECTS);
    }

    @Test
    void create_returnsLeft_whenAddingSubProjectToProjectWithMilestones() {
        ProjectEntity root = projectEntity();
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(root)).thenReturn(Optional.empty());
        when(milestoneRepository.existsByProjectId(root.getId())).thenReturn(true);

        SpendingEventCreateRequest request = fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-AB")
                .subProject(SubProjectRequest.builder().subProjectId("WP-1").projectTitle("WP").build())
                .milestones(List.of(fundingMilestone("MS-1", ALLOCATED)))
                .build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.SUBPROJECT_NOT_ALLOWED_WITH_MILESTONES);
    }

    // --- update / publish / delete ---

    @Test
    void update_returnsLeft_whenPublished() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.SPENDING, EventStatus.PUBLISHED)));

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.update("e1",
                fundingRequest(fundingMilestone("MS-1", ALLOCATED)));

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
    }

    @Test
    void update_success_replacesAllocations() {
        FundingEventEntity existing = eventEntity(EventType.FUNDING, EventStatus.DRAFT);
        stubExistingProjectAndMilestone("MS-1");
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(existing));
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.update("e1",
                fundingRequest(fundingMilestone("MS-1", ALLOCATED)));

        assertThat(result.isRight()).isTrue();
    }

    @Test
    void publish_setsStatusAndDispatchApproved() {
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(event));
        when(fundingEventRepository.saveAndFlush(event)).thenReturn(event);

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.publish("e1");

        assertThat(result.isRight()).isTrue();
        assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(event.isLedgerDispatchApproved()).isTrue();
    }

    @Test
    void publish_returnsLeft_whenAlreadyPublished() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.SPENDING, EventStatus.PUBLISHED)));

        assertThat(spendingEventService.publish("e1").getLeft().getTitle()).isEqualTo("SPENDING_EVENT_ALREADY_PUBLISHED");
    }

    @Test
    void delete_returns204_forDraft() {
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(event));

        assertThat(spendingEventService.delete("e1").isRight()).isTrue();
        verify(fundingEventRepository).delete(event);
    }

    @Test
    void delete_returnsLeft_whenPublished() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.SPENDING, EventStatus.PUBLISHED)));

        assertThat(spendingEventService.delete("e1").isLeft()).isTrue();
        verify(fundingEventRepository, never()).delete(any());
    }

    // --- toView / toPublishView ---

    @Test
    void toView_mapsScalarsAndAllocationSpendDetail() {
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        MilestoneEntity milestone = milestoneEntity("m1");
        EventMilestoneAllocationEntity alloc = spendingAllocation("e1", "m1");
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of(alloc));
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));

        SpendingEventView view = spendingEventService.toView(event);

        assertThat(view.getEventId()).isEqualTo("e1");
        assertThat(view.getProjectAllocations()).hasSize(1);
        var mv = view.getProjectAllocations().get(0).getMilestoneAllocations().get(0);
        assertThat(mv.getAllocatedAmount()).isEqualByComparingTo(ALLOCATED);
        assertThat(mv.getAmountFcy()).isEqualByComparingTo(AMOUNT_FCY);
        assertThat(mv.getVendor()).isEqualTo("Vendor AB");
    }

    @Test
    void toPublishView_mapsScalarsAndSpendDetail() {
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        event.setCreatedAt(LocalDateTime.of(2025, 6, 15, 10, 0));
        event.setTotalAmount(new BigDecimal("300.00"));
        MilestoneEntity milestone = milestoneEntity("m1");
        EventMilestoneAllocationEntity alloc = spendingAllocation("e1", "m1");
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of(alloc));
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getEventId()).isEqualTo("e1");
        assertThat(view.getDate()).isEqualTo(LocalDate.of(2025, 6, 15));
        assertThat(view.getCurrency().getCustCode()).isEqualTo("USD");
        assertThat(view.getProjectAllocations()).hasSize(1);
        SpendingEventPublishView.Milestone m = view.getProjectAllocations().get(0).getMilestones().get(0);
        assertThat(m.getAllocatedAmount()).isEqualByComparingTo(ALLOCATED);
        assertThat(m.getAmountFcy()).isEqualByComparingTo(AMOUNT_FCY);
        assertThat(m.getSpendCurrency().getCustCode()).isEqualTo("EUR");
        assertThat(m.getVendor()).isEqualTo("Vendor AB");
    }

    // --- view-returning API + org access ---

    @Test
    void listEvents_returns401_whenUserCannotAccessOrg() {
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        PagedResponse<SpendingEventView> result = spendingEventService.listEvents(
                "org1", Optional.empty(), Optional.empty(), PAGEABLE);

        assertThat(result.getError().orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void listEventsByProject_returns404_whenProjectNotFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.empty());

        PagedResponse<SpendingEventView> result = spendingEventService.listEventsByProject(
                "p1", Optional.empty(), Optional.empty(), PAGEABLE);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    @Test
    void getEvent_returns404_whenNotFound() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.empty());

        assertThat(spendingEventService.getEvent("e1").getError().orElseThrow().getTitle())
                .isEqualTo(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
    }

    @Test
    void getEvent_returns401_whenUserCannotAccessOrg() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.SPENDING, EventStatus.DRAFT)));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        assertThat(spendingEventService.getEvent("e1").getError().orElseThrow().getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void getEvent_returnsView_whenAuthorised() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.SPENDING, EventStatus.DRAFT)));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of());

        SpendingEventView result = spendingEventService.getEvent("e1");

        assertThat(result.getError()).isEmpty();
        assertThat(result.getEventId()).isEqualTo("e1");
    }

    @Test
    void createEvent_returnsErrorView_whenCreateFails() {
        SpendingEventView result = spendingEventService.createEvent(SpendingEventCreateRequest.builder()
                .organisationId("org1").eventType(EventType.SPENDING).fundingId("GRANT-2025-001").currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder().externalProjectId(null)
                        .milestones(List.of(fundingMilestone("MS-1", ALLOCATED))).build()))
                .build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo("PROJECT_FIELDS_REQUIRED");
    }

    @Test
    void updateEvent_returns401_whenUserCannotAccessOrg() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.SPENDING, EventStatus.DRAFT)));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        SpendingEventView result = spendingEventService.updateEvent("e1", fundingRequest(fundingMilestone("MS-1", ALLOCATED)));

        assertThat(result.getError().orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void publishEvent_returns401_whenUserCannotAccessOrg() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.SPENDING, EventStatus.DRAFT)));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        assertThat(spendingEventService.publishEvent("e1").getError().orElseThrow().getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void deleteEvent_returns401_whenUserCannotAccessOrg() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.SPENDING, EventStatus.DRAFT)));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        Optional<ProblemDetail> result = spendingEventService.deleteEvent("e1");

        assertThat(result.orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(fundingEventRepository, never()).delete(any());
    }

    @Test
    void deleteEvent_returnsEmpty_whenAuthorisedAndDeleted() {
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(event));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);

        assertThat(spendingEventService.deleteEvent("e1")).isEmpty();
        verify(fundingEventRepository).delete(event);
    }

    // --- helpers ---

    private void stubExistingProjectAndMilestone(String externalMilestoneId) {
        ProjectEntity project = projectEntity();
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(project));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(project.getId(), externalMilestoneId))
                .thenReturn(Optional.of(milestoneEntity("m1")));
    }

    private FundingEventEntity eventEntity(EventType type, EventStatus status) {
        return FundingEventEntity.builder()
                .id("e1").eventType(type).status(status).organisationId("org1")
                .fundingId("GRANT-2025-001").currency("USD").totalAmount(BigDecimal.ZERO).build();
    }

    private ProjectEntity projectEntity() {
        return ProjectEntity.builder().id("p1").organisationId("org1").fundingId("GRANT-2025-001")
                .externalProjectId("PROJ-AB").projectTitle("Project AB")
                .totalAmount(new BigDecimal("200000.00")).currency("USD").build();
    }

    private MilestoneEntity milestoneEntity(String id) {
        return milestoneEntityWithAmount(id, new BigDecimal("50000.00"));
    }

    private MilestoneEntity milestoneEntityWithAmount(String id, BigDecimal amount) {
        return MilestoneEntity.builder().id(id).milestoneTitle("Milestone AB").milestoneAmount(amount)
                .currency("USD").milestoneDate(FUTURE_DATE).project(projectEntity()).build();
    }

    private EventMilestoneAllocationEntity spendingAllocation(String eventId, String milestoneId) {
        return EventMilestoneAllocationEntity.builder()
                .id(new EventMilestoneAllocationEntity.Id(eventId, milestoneId))
                .allocatedAmount(ALLOCATED).category("Personnel").vendor("Vendor AB")
                .amountFcy(AMOUNT_FCY).amountRcy(AMOUNT_RCY).currency("EUR").fxRate(FX_RATE)
                .spendDate(LocalDate.of(2025, 4, 3)).build();
    }

    /** A milestone allocation for a FUNDING/REFUND event: allocatedAmount only, no spend detail. */
    private EventMilestoneAllocationRequest fundingMilestone(String externalMilestoneId, BigDecimal allocated) {
        return EventMilestoneAllocationRequest.builder()
                .milestone(MilestoneCreateRequest.builder().externalMilestoneId(externalMilestoneId).build())
                .allocatedAmount(allocated).build();
    }

    /** A milestone allocation for a SPENDING event: allocatedAmount + consistent spend detail. */
    private EventMilestoneAllocationRequest spendingMilestone(String externalMilestoneId) {
        return EventMilestoneAllocationRequest.builder()
                .milestone(MilestoneCreateRequest.builder().externalMilestoneId(externalMilestoneId).build())
                .allocatedAmount(ALLOCATED)
                .category("Personnel").vendor("Vendor AB").amountFcy(AMOUNT_FCY).currency("EUR")
                .fxRate(FX_RATE).amountRcy(AMOUNT_RCY).spendDate(LocalDate.of(2025, 4, 3)).build();
    }

    private SpendingEventCreateRequest fundingRequest(EventMilestoneAllocationRequest milestone) {
        return fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-AB").milestones(List.of(milestone)).build());
    }

    private SpendingEventCreateRequest fundingRequest(EventProjectAllocationRequest allocation) {
        return SpendingEventCreateRequest.builder()
                .organisationId("org1").eventType(EventType.FUNDING).fundingId("GRANT-2025-001").currency("USD")
                .allocations(List.of(allocation)).build();
    }

    private SpendingEventCreateRequest spendingRequest(EventMilestoneAllocationRequest milestone) {
        return SpendingEventCreateRequest.builder()
                .organisationId("org1").eventType(EventType.SPENDING).fundingId("GRANT-2025-001").currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId("PROJ-AB").milestones(List.of(milestone)).build()))
                .build();
    }
}
