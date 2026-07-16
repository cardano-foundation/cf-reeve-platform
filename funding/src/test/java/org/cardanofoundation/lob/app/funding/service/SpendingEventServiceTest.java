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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
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
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
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
    @Mock
    private OrganisationPublicApiIF organisationPublicApi;
    @Mock
    private FundingCascadeDeleteService cascadeDeleteService;

    private SpendingEventService spendingEventService;

    /**
     * The collaborating services are real instances over the mocked repositories, so the tests
     * exercise the full resolve-or-create logic while stubbing only at the repository level.
     */
    @BeforeEach
    void wireService() {
        MilestoneService milestoneService = new MilestoneService(
                milestoneRepository, projectRepository, milestoneAllocationRepository, keycloakSecurityHelper, cascadeDeleteService);
        ProjectStructureService projectStructureService = new ProjectStructureService(projectRepository, milestoneService);
        spendingEventService = new SpendingEventService(fundingEventRepository, projectRepository,
                milestoneAllocationRepository, milestoneService, projectStructureService,
                keycloakSecurityHelper, organisationPublicApi);
    }

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
    void create_spendingEvent_success_setsSpendDetailOnEvent() {
        stubExistingProjectAndMilestone("MS-1");
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(
                spendingRequest(fundingMilestone("MS-1", ALLOCATED)));

        assertThat(result.isRight()).isTrue();
        verify(fundingEventRepository).saveAndFlush(argThat(e ->
                e.getTotalAmount().compareTo(ALLOCATED) == 0
                        && e.getAmountFcy().compareTo(AMOUNT_FCY) == 0
                        && e.getAmountRcy().compareTo(AMOUNT_RCY) == 0
                        && "Vendor AB".equals(e.getVendor())));
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
    void create_returnsLeft_whenNewRootProjectTitleAlreadyExists() {
        // Auto-creating a root project via the allocation flow must honour root-title uniqueness.
        when(projectRepository.existsById(any())).thenReturn(false);
        when(projectRepository.existsByOrganisationIdAndProjectTitleAndParentProjectIsNull("org1", "New Project"))
                .thenReturn(true);

        SpendingEventCreateRequest request = fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-NEW").projectTitle("New Project").fundingId("GRANT-2025-001")
                .totalAmount(new BigDecimal("100000.00")).currency("USD")
                .milestones(List.of(EventMilestoneAllocationRequest.builder()
                        .milestone(MilestoneCreateRequest.builder().milestoneTitle("New MS")
                                .milestoneAmount(new BigDecimal("60000.00")).currency("USD").milestoneDate(FUTURE_DATE).build())
                        .allocatedAmount(ALLOCATED).build()))
                .build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS);
        verify(projectRepository, never()).saveAndFlush(any());
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
        // FUNDING event carrying event-level spend detail — rejected before allocations are touched.
        SpendingEventCreateRequest request = fundingRequest(fundingMilestone("MS-1", ALLOCATED));
        request.setAmountFcy(AMOUNT_FCY);

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.SPEND_FIELDS_NOT_ALLOWED);
    }

    @Test
    void create_returnsLeft_whenSpendingEventMissingSpendFields() {
        // SPENDING event with allocations but no amountFcy/amountRcy/fxRate on the event.
        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .organisationId("org1").eventType(EventType.SPENDING).fundingId("GRANT-2025-001").currencyRcy("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId("PROJ-AB").milestones(List.of(fundingMilestone("MS-1", ALLOCATED))).build()))
                .build();

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.SPEND_FIELDS_REQUIRED);
    }

    @Test
    void create_returnsLeft_whenFxRateDoesNotMatch() {
        SpendingEventCreateRequest request = spendingRequest(fundingMilestone("MS-1", ALLOCATED));
        request.setFxRate(new BigDecimal("3")); // 50000 * 3 = 150000 != amountFcy 100000

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.FX_RATE_MISMATCH);
    }

    @Test
    void create_returnsLeft_whenAllocatedTotalExceedsAmountRcy() {
        stubExistingProjectAndMilestone("MS-1");

        // allocated total (50000) exceeds the event's spend amountRcy (40000); fx stays consistent (40000 * 2.5 = 100000)
        SpendingEventCreateRequest request = spendingRequest(fundingMilestone("MS-1", ALLOCATED));
        request.setAmountRcy(new BigDecimal("40000.00"));
        request.setFxRate(new BigDecimal("2.5"));

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.ALLOCATION_EXCEEDS_SPEND);
    }

    @Test
    void create_returnsLeft_whenSpendNotFullyAllocated() {
        stubExistingProjectAndMilestone("MS-1");

        // Only 30000 of the 50000 spend (amountRcy) is allocated — a SPENDING event must be fully allocated.
        SpendingEventCreateRequest request = spendingRequest(fundingMilestone("MS-1", new BigDecimal("30000.00")));

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.SPEND_NOT_FULLY_ALLOCATED);
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenAmountRcyExceedsMilestoneBudget() {
        stubExistingProjectAndMilestone("MS-1"); // milestone budget 50000, project 200000

        // amountRcy (60000) exceeds the summed milestone budget (50000); fx stays consistent (60000 * 2 = 120000)
        SpendingEventCreateRequest request = spendingRequest(fundingMilestone("MS-1", ALLOCATED));
        request.setAmountRcy(new BigDecimal("60000.00"));
        request.setAmountFcy(new BigDecimal("120000.00"));

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.EVENT_AMOUNT_EXCEEDS_MILESTONES);
    }

    @Test
    void create_returnsLeft_whenAmountRcyExceedsProjectBudget() {
        // Milestone with no budget -> milestone cap is lifted, project total (200000) still bounds the spend.
        ProjectEntity project = projectEntity();
        MilestoneEntity milestone = milestoneEntityWithAmount("m1", null);
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(project));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(project.getId(), "MS-1")).thenReturn(Optional.of(milestone));

        // amountRcy (250000) exceeds the project total (200000); fx stays consistent (250000 * 2 = 500000)
        SpendingEventCreateRequest request = spendingRequest(fundingMilestone("MS-1", ALLOCATED));
        request.setAmountRcy(new BigDecimal("250000.00"));
        request.setAmountFcy(new BigDecimal("500000.00"));

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.EVENT_AMOUNT_EXCEEDS_PROJECT);
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
    void create_acceptsNewMilestoneWithDateInPast() {
        // Historic data may be recorded — past milestone dates are allowed.
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(projectEntity()));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(any(), eq("MS-NEW"))).thenReturn(Optional.empty());
        when(milestoneRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SpendingEventCreateRequest request = fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-AB")
                .milestones(List.of(EventMilestoneAllocationRequest.builder()
                        .milestone(MilestoneCreateRequest.builder().externalMilestoneId("MS-NEW").milestoneTitle("MS")
                                .milestoneAmount(new BigDecimal("50000.00")).currency("USD")
                                .milestoneDate(LocalDate.now().minusDays(1)).build())
                        .allocatedAmount(ALLOCATED).build()))
                .build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isRight()).isTrue();
        verify(milestoneRepository).saveAndFlush(any());
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
                .subProjects(List.of(EventSubProjectAllocationRequest.builder()
                        .externalProjectId("WP-1").projectTitle("WP")
                        .milestones(List.of(fundingMilestone("MS-1", ALLOCATED))).build()))
                .build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.SUBPROJECT_NOT_ALLOWED_WITH_MILESTONES);
    }

    @Test
    void create_createsSubProjectTreeWithBudget_onTheFly() {
        ProjectEntity root = projectEntity(); // id "p1", total 200000
        String rootId = ProjectEntity.id("org1", "PROJ-AB");
        String subId = ProjectEntity.subId("p1", "WP-1");
        when(projectRepository.existsById(rootId)).thenReturn(true);
        when(projectRepository.findById(rootId)).thenReturn(Optional.of(root));
        when(projectRepository.findById(subId)).thenReturn(Optional.empty());
        when(milestoneRepository.existsByProjectId("p1")).thenReturn(false);
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of());
        when(projectRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(subId, "MS-1"))
                .thenReturn(Optional.of(milestoneEntityWithAmount("m1", new BigDecimal("50000.00"))));
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SpendingEventCreateRequest request = fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-AB")
                .subProjects(List.of(EventSubProjectAllocationRequest.builder()
                        .externalProjectId("WP-1").projectTitle("Work Package 1")
                        .totalAmount(new BigDecimal("100000.00")).currency("USD")
                        .milestones(List.of(fundingMilestone("MS-1", ALLOCATED))).build()))
                .build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isRight()).isTrue();
        verify(projectRepository).saveAndFlush(argThat(p ->
                p.getParentProject() != null && "p1".equals(p.getParentProject().getId())
                        && new BigDecimal("100000.00").compareTo(p.getTotalAmount()) == 0
                        && p.getId().equals(ProjectEntity.subId("p1", "WP-1"))));
    }

    @Test
    void create_returnsLeft_whenSiblingSubProjectsShareTitleUnderSameParent() {
        // QA repro: one parent with two sub-projects of the same title in a single event create.
        ProjectEntity root = projectEntity(); // "p1" / PROJ-AB
        String rootId = ProjectEntity.id("org1", "PROJ-AB");
        when(projectRepository.existsById(rootId)).thenReturn(true);
        when(projectRepository.findById(rootId)).thenReturn(Optional.of(root));

        SpendingEventCreateRequest request = fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-AB")
                .subProjects(List.of(
                        EventSubProjectAllocationRequest.builder().externalProjectId("WP-1").projectTitle("Shared Package")
                                .totalAmount(new BigDecimal("50000.00")).currency("USD")
                                .milestones(List.of(fundingMilestone("MS-1", ALLOCATED))).build(),
                        EventSubProjectAllocationRequest.builder().externalProjectId("WP-2").projectTitle("Shared Package")
                                .totalAmount(new BigDecimal("50000.00")).currency("USD")
                                .milestones(List.of(fundingMilestone("MS-2", ALLOCATED))).build()))
                .build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenNewSubProjectAmountExceedsParent() {
        ProjectEntity root = projectEntity(); // total 200000
        String rootId = ProjectEntity.id("org1", "PROJ-AB");
        when(projectRepository.existsById(rootId)).thenReturn(true);
        when(projectRepository.findById(rootId)).thenReturn(Optional.of(root));
        when(projectRepository.findById(ProjectEntity.subId("p1", "WP-1"))).thenReturn(Optional.empty());
        when(milestoneRepository.existsByProjectId("p1")).thenReturn(false);
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of());

        SpendingEventCreateRequest request = fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-AB")
                .subProjects(List.of(EventSubProjectAllocationRequest.builder()
                        .externalProjectId("WP-1").projectTitle("WP")
                        .totalAmount(new BigDecimal("250000.00")).currency("USD")
                        .milestones(List.of(fundingMilestone("MS-1", ALLOCATED))).build()))
                .build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.SUBPROJECT_AMOUNT_EXCEEDS_PARENT);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    // --- create: id-only references must resolve to existing entities ---

    @Test
    void create_returnsLeft_whenReferencedProjectNotFound() {
        // Only externalProjectId supplied (no projectTitle) → referencing an existing project that does not exist.
        when(projectRepository.existsById(any())).thenReturn(false);

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(
                fundingRequest(fundingMilestone("MS-1", ALLOCATED)));

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenReferencedMilestoneNotFound() {
        // Project exists, milestone referenced by id only (no create fields) but does not exist.
        ProjectEntity project = projectEntity();
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(project));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(project.getId(), "MS-1"))
                .thenReturn(Optional.empty());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(
                fundingRequest(fundingMilestone("MS-1", ALLOCATED)));

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_NOT_FOUND);
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenNewProjectFundingIdAlreadyUsed() {
        // The new project's fundingId is already claimed by another project of the organisation
        // (DB constraint uq_funding_project_org_funding_id) — clean 409 instead of a 500.
        when(projectRepository.existsById(any())).thenReturn(false);
        when(projectRepository.existsByOrganisationIdAndFundingId("org1", "GRANT-2025-001")).thenReturn(true);

        SpendingEventCreateRequest request = fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-NEW").projectTitle("New Project").fundingId("GRANT-2025-001")
                .totalAmount(new BigDecimal("100000.00")).currency("USD")
                .milestones(List.of(fundingMilestone("MS-1", ALLOCATED)))
                .build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_FUNDING_ID_ALREADY_USED);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenNewSubProjectFundingIdAlreadyUsed() {
        ProjectEntity root = projectEntity(); // id "p1"
        String rootId = ProjectEntity.id("org1", "PROJ-AB");
        when(projectRepository.existsById(rootId)).thenReturn(true);
        when(projectRepository.findById(rootId)).thenReturn(Optional.of(root));
        when(projectRepository.findById(ProjectEntity.subId("p1", "WP-1"))).thenReturn(Optional.empty());
        when(milestoneRepository.existsByProjectId("p1")).thenReturn(false);
        when(projectRepository.existsByOrganisationIdAndFundingId("org1", "GRANT-2025-001-SUB")).thenReturn(true);

        SpendingEventCreateRequest request = fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-AB")
                .subProjects(List.of(EventSubProjectAllocationRequest.builder()
                        .externalProjectId("WP-1").projectTitle("WP").fundingId("GRANT-2025-001-SUB")
                        .totalAmount(new BigDecimal("100000.00")).currency("USD")
                        .milestones(List.of(fundingMilestone("MS-1", ALLOCATED))).build()))
                .build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_FUNDING_ID_ALREADY_USED);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenReferencedSubProjectNotFound() {
        // Root exists; sub-project referenced by id only (no projectTitle) but does not exist under the parent.
        ProjectEntity root = projectEntity();
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(root)).thenReturn(Optional.empty());

        SpendingEventCreateRequest request = fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-AB")
                .subProjects(List.of(EventSubProjectAllocationRequest.builder()
                        .externalProjectId("WP-1").build()))
                .build());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
        verify(fundingEventRepository, never()).saveAndFlush(any());
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
    void update_returnsLeft_whenOrganisationMismatch() {
        // The body claims a different organisation than the event's — must not re-target its projects.
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.FUNDING, EventStatus.DRAFT)));

        SpendingEventCreateRequest request = fundingRequest(fundingMilestone("MS-1", ALLOCATED));
        request.setOrganisationId("other-org");

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.update("e1", request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.ORGANISATION_MISMATCH);
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returnsLeft_whenEventTypeChanged() {
        // The stored event is SPENDING; the update body claims FUNDING — the type is immutable.
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.SPENDING, EventStatus.DRAFT)));

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.update("e1",
                fundingRequest(fundingMilestone("MS-1", ALLOCATED)));

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.EVENT_TYPE_IMMUTABLE);
        verify(fundingEventRepository, never()).saveAndFlush(any());
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
    void toView_mapsScalarsAndEventSpendDetail() {
        FundingEventEntity event = spendingEventEntity();
        MilestoneEntity milestone = milestoneEntity("m1");
        EventMilestoneAllocationEntity alloc = spendingAllocation("e1", "m1");
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of(alloc));
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));

        SpendingEventView view = spendingEventService.toView(event);

        assertThat(view.getEventId()).isEqualTo("e1");
        assertThat(view.getAmountFcy()).isEqualByComparingTo(AMOUNT_FCY);
        assertThat(view.getCurrencyFcy()).isEqualTo("EUR");
        assertThat(view.getVendor()).isEqualTo("Vendor AB");
        assertThat(view.getProjectAllocations()).hasSize(1);
        var mv = view.getProjectAllocations().get(0).getMilestoneAllocations().get(0);
        assertThat(mv.getAllocatedAmount()).isEqualByComparingTo(ALLOCATED);
    }

    @Test
    void toPublishView_mapsScalarsAndEventSpendDetail() {
        FundingEventEntity event = spendingEventEntity();
        event.setStatus(EventStatus.PUBLISHED);
        event.setCreatedAt(LocalDateTime.of(2025, 6, 15, 10, 0));
        event.setTotalAmount(new BigDecimal("300.00"));
        MilestoneEntity milestone = milestoneEntity("m1");
        EventMilestoneAllocationEntity alloc = spendingAllocation("e1", "m1");
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of(alloc));
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getEventId()).isEqualTo("e1");
        assertThat(view.getEventDate()).isEqualTo(LocalDate.of(2025, 4, 3));
        assertThat(view.getCurrencyRcy().getCustCode()).isEqualTo("USD");
        assertThat(view.getAmountFcy()).isEqualByComparingTo(AMOUNT_FCY);
        assertThat(view.getCurrencyFcy().getCustCode()).isEqualTo("EUR");
        assertThat(view.getVendor()).isEqualTo("Vendor AB");
        assertThat(view.getProjectAllocations()).hasSize(1);
        // Direct allocation (project has no parent): milestones at the project level, no sub-project.
        SpendingEventPublishView.ProjectAllocation allocation = view.getProjectAllocations().get(0);
        assertThat(allocation.getSubProject()).isNull();
        SpendingEventPublishView.Milestone m = allocation.getMilestones().get(0);
        assertThat(m.getAllocatedAmount()).isEqualByComparingTo(ALLOCATED);
    }

    @Test
    void toPublishView_nestsSubProjectAllocation_underRootProject() {
        FundingEventEntity event = spendingEventEntity();
        event.setCreatedAt(LocalDateTime.of(2025, 6, 15, 10, 0));
        event.setTotalAmount(new BigDecimal("300.00"));

        // The allocated milestone belongs to a sub-project of PROJ-AB.
        ProjectEntity root = projectEntity(); // PROJ-AB / "Project AB"
        ProjectEntity subProject = ProjectEntity.builder().id("sub1").organisationId("org1")
                .externalProjectId("SUB-1").projectTitle("Sub Project One")
                .totalAmount(new BigDecimal("100000.00")).currency("USD")
                .parentProject(root).build();
        MilestoneEntity milestone = MilestoneEntity.builder().id("m1").milestoneTitle("Milestone AB")
                .milestoneAmount(new BigDecimal("50000.00")).currency("USD").milestoneDate(FUTURE_DATE)
                .project(subProject).build();
        EventMilestoneAllocationEntity alloc = spendingAllocation("e1", "m1");
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of(alloc));
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        SpendingEventPublishView.ProjectAllocation allocation = view.getProjectAllocations().get(0);
        // The root project is published as is ...
        assertThat(allocation.getExternalProjectId()).isEqualTo("PROJ-AB");
        assertThat(allocation.getProjectTitle()).isEqualTo("Project AB");
        // ... with no milestones at the project level ...
        assertThat(allocation.getMilestones()).isNull();
        // ... and the sub-project carries its own id, title and the milestone allocations.
        SpendingEventPublishView.SubProject sub = allocation.getSubProject();
        assertThat(sub.getSubProjectId()).isEqualTo("SUB-1");
        assertThat(sub.getSubProjectTitle()).isEqualTo("Sub Project One");
        assertThat(sub.getMilestones()).hasSize(1);
        assertThat(sub.getMilestones().get(0).getAllocatedAmount()).isEqualByComparingTo(ALLOCATED);
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
    void listEvents_returnsError_whenOrganisationNotFound() {
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.empty());

        PagedResponse<SpendingEventView> result = spendingEventService.listEvents(
                "org1", Optional.empty(), Optional.empty(), PAGEABLE);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.ORGANISATION_NOT_FOUND);
    }

    @Test
    void listEvents_returnsPage_whenOrganisationExists() {
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(mock(Organisation.class)));
        when(fundingEventRepository.findByOrganisationIdAndFilter("org1", null, null, PAGEABLE))
                .thenReturn(new PageImpl<>(List.of(eventEntity(EventType.SPENDING, EventStatus.DRAFT))));
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of());

        PagedResponse<SpendingEventView> result = spendingEventService.listEvents(
                "org1", Optional.empty(), Optional.empty(), PAGEABLE);

        assertThat(result.getError()).isEmpty();
        assertThat(result.getContent()).hasSize(1);
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
                .organisationId("org1").eventType(EventType.FUNDING).fundingId("GRANT-2025-001")
                .fundingEntity("Cardano Foundation").currencyRcy("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder().externalProjectId(null)
                        .milestones(List.of(fundingMilestone("MS-1", ALLOCATED))).build()))
                .build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo("PROJECT_FIELDS_REQUIRED");
    }

    @Test
    void create_returnsLeft_whenFundingEventMissingFundingEntity() {
        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .organisationId("org1").eventType(EventType.FUNDING).fundingId("GRANT-2025-001").currencyRcy("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId("PROJ-AB").milestones(List.of(fundingMilestone("MS-1", ALLOCATED))).build()))
                .build();

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.FUNDING_ENTITY_REQUIRED);
        verify(fundingEventRepository, never()).saveAndFlush(any());
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
                .fundingId("GRANT-2025-001").currencyRcy("USD").totalAmount(BigDecimal.ZERO).build();
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

    /** A SPENDING event entity carrying its single (consistent) spend record. */
    private FundingEventEntity spendingEventEntity() {
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        event.setCategory("Personnel");
        event.setVendor("Vendor AB");
        event.setAmountFcy(AMOUNT_FCY);
        event.setAmountRcy(AMOUNT_RCY);
        event.setCurrencyFcy("EUR");
        event.setFxRate(FX_RATE);
        event.setEventDate(LocalDate.of(2025, 4, 3));
        return event;
    }

    private EventMilestoneAllocationEntity spendingAllocation(String eventId, String milestoneId) {
        return EventMilestoneAllocationEntity.builder()
                .id(new EventMilestoneAllocationEntity.Id(eventId, milestoneId))
                .allocatedAmount(ALLOCATED).build();
    }

    /** A milestone allocation: allocatedAmount only (spend detail lives on the event). */
    private EventMilestoneAllocationRequest fundingMilestone(String externalMilestoneId, BigDecimal allocated) {
        return EventMilestoneAllocationRequest.builder()
                .milestone(MilestoneCreateRequest.builder().externalMilestoneId(externalMilestoneId).build())
                .allocatedAmount(allocated).build();
    }

    private SpendingEventCreateRequest fundingRequest(EventMilestoneAllocationRequest milestone) {
        return fundingRequest(EventProjectAllocationRequest.builder()
                .externalProjectId("PROJ-AB").milestones(List.of(milestone)).build());
    }

    private SpendingEventCreateRequest fundingRequest(EventProjectAllocationRequest allocation) {
        return SpendingEventCreateRequest.builder()
                .organisationId("org1").eventType(EventType.FUNDING).fundingId("GRANT-2025-001")
                .fundingEntity("Cardano Foundation").currencyRcy("USD")
                .allocations(List.of(allocation)).build();
    }

    /** A SPENDING event request with event-level spend detail (consistent: 100000 = 50000 * 2). */
    private SpendingEventCreateRequest spendingRequest(EventMilestoneAllocationRequest milestone) {
        return SpendingEventCreateRequest.builder()
                .organisationId("org1").eventType(EventType.SPENDING).fundingId("GRANT-2025-001").currencyRcy("USD")
                .category("Personnel").vendor("Vendor AB").amountFcy(AMOUNT_FCY).currencyFcy("EUR")
                .fxRate(FX_RATE).amountRcy(AMOUNT_RCY).eventDate(LocalDate.of(2025, 4, 3))
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId("PROJ-AB").milestones(List.of(milestone)).build()))
                .build();
    }
}
