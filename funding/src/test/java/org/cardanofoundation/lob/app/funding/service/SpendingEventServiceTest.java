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
    private SpendingItemRepository spendingItemRepository;
    @Mock
    private EventMilestoneAllocationRepository milestoneAllocationRepository;
    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @InjectMocks
    private SpendingEventService spendingEventService;

    private static final Pageable VIEW_PAGEABLE = PageRequest.of(0, 10);

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

    // --- findByOrganisationIdAndFilter ---

    @Test
    void findByOrganisationIdAndFilter_delegatesToRepository() {
        Pageable pageable = PageRequest.of(0, 10);
        var page = new PageImpl<>(List.of(eventEntity(EventType.SPENDING, EventStatus.DRAFT)));
        when(fundingEventRepository.findByOrganisationIdAndFilter("org1", EventStatus.DRAFT, EventType.SPENDING, pageable))
                .thenReturn(page);

        assertThat(spendingEventService.findByOrganisationIdAndFilter(
                "org1", Optional.of(EventStatus.DRAFT), Optional.of(EventType.SPENDING), pageable))
                .isEqualTo(page);
    }

    @Test
    void findByOrganisationIdAndFilter_passesNulls_whenFiltersEmpty() {
        Pageable pageable = PageRequest.of(0, 10);
        var page = new PageImpl<FundingEventEntity>(List.of());
        when(fundingEventRepository.findByOrganisationIdAndFilter("org1", null, null, pageable)).thenReturn(page);

        assertThat(spendingEventService.findByOrganisationIdAndFilter("org1", Optional.empty(), Optional.empty(), pageable))
                .isEqualTo(page);
    }

    // --- create ---

    @Test
    void create_returnsLeft_whenNewProjectMissingRequiredFields() {
        // projectId is user-defined; not found → service tries to create, but projectTitle is absent
        when(projectRepository.existsById(any())).thenReturn(false);

        SpendingEventCreateRequest request = requestWithExistingProject("PROJ-MISSING", "MS-1");
        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("PROJECT_FIELDS_REQUIRED");
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenMilestoneIdNotFound() {
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(projectEntity()));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(any(), eq("MS-MISSING"))).thenReturn(Optional.empty());

        SpendingEventCreateRequest request = requestWithExistingProject("PROJ-AB", "MS-MISSING");
        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("MILESTONE_NOT_FOUND");
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_successWithExistingProjectAndMilestone() {
        ProjectEntity project = projectEntity();
        MilestoneEntity milestone = milestoneEntity("m1");
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(project));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(project.getId(), "MS-1")).thenReturn(Optional.of(milestone));
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SpendingEventCreateRequest request = requestWithExistingProject("PROJ-AB", "MS-1");
        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isRight()).isTrue();
        verify(milestoneRepository, never()).saveAndFlush(any());
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_successWithNewProjectAndNewMilestone() {
        when(projectRepository.existsById(any())).thenReturn(false);
        when(projectRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(milestoneRepository.saveAndFlush(any())).thenAnswer(i -> {
            MilestoneEntity m = i.getArgument(0);
            if (m.getId() == null) m = MilestoneEntity.builder().id("m-new").milestoneTitle(m.getMilestoneTitle())
                    .milestoneAmount(m.getMilestoneAmount()).currency(m.getCurrency()).milestoneDate(m.getMilestoneDate())
                    .project(projectEntity()).build();
            return m;
        });
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SpendingEventCreateRequest request = requestWithNewProjectAndMilestone();
        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isRight()).isTrue();
        verify(projectRepository).saveAndFlush(any());
        verify(milestoneRepository).saveAndFlush(any());
    }

    @Test
    void create_spendingEvent_calculatesTotalFromItems() {
        ProjectEntity project = projectEntity();
        MilestoneEntity milestone = milestoneEntity("m1");
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(project));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(project.getId(), "MS-1")).thenReturn(Optional.of(milestone));
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .organisationId("org1")
                .eventType(EventType.SPENDING)
                .fundingId("GRANT-2025-001")
                .currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId("PROJ-AB")
                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                .milestone(MilestoneCreateRequest.builder().externalMilestoneId("MS-1").build())
                                .build()))
                        .build()))
                .items(List.of(
                        itemRequest(new BigDecimal("100.00")),
                        itemRequest(new BigDecimal("200.00"))))
                .build();

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isRight()).isTrue();
        verify(fundingEventRepository).saveAndFlush(argThat(e ->
                e.getTotalAmount().compareTo(new BigDecimal("300.00")) == 0
                && e.getSpendingItems().size() == 2
        ));
    }

    @Test
    void create_fundingEvent_calculatesTotalFromAllocatedAmounts() {
        ProjectEntity project = projectEntity();
        MilestoneEntity milestone = milestoneEntity("m1");
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(project));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(project.getId(), "MS-1")).thenReturn(Optional.of(milestone));
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .organisationId("org1")
                .eventType(EventType.FUNDING)
                .fundingId("GRANT-2025-001")
                .currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId("PROJ-AB")
                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                .milestone(MilestoneCreateRequest.builder().externalMilestoneId("MS-1").build())
                                .allocatedAmount(new BigDecimal("50000.00"))
                                .build()))
                        .build()))
                .build();

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isRight()).isTrue();
        verify(fundingEventRepository).saveAndFlush(argThat(e ->
                e.getTotalAmount().compareTo(new BigDecimal("50000.00")) == 0
        ));
    }

    @Test
    void create_returnsLeft_whenNewMilestoneFieldsMissing() {
        ProjectEntity project = projectEntity();
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(project));

        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .organisationId("org1")
                .eventType(EventType.SPENDING)
                .fundingId("GRANT-2025-001")
                .currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId("PROJ-AB")
                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                .milestone(MilestoneCreateRequest.builder().build()) // missing all fields
                                .build()))
                        .build()))
                .build();

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("MILESTONE_FIELDS_REQUIRED");
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenEventAlreadyExists() {
        when(fundingEventRepository.existsById(any())).thenReturn(true);

        Either<ProblemDetail, FundingEventEntity> result =
                spendingEventService.create(requestWithExistingProject("PROJ-AB", "MS-1"));

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_EXISTS);
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenSpendingItemMissingRequiredFields() {
        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .organisationId("org1").eventType(EventType.SPENDING).fundingId("GRANT-2025-001").currency("USD")
                .allocations(List.of())
                .items(List.of(SpendingItemRequest.builder()
                        .currency("USD").spendDate(LocalDate.of(2025, 4, 3)).build())) // no category/vendor/amountFcy
                .build();

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("ITEM_FIELDS_REQUIRED");
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenFundingItemMissingAmountRcy() {
        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .organisationId("org1").eventType(EventType.FUNDING).fundingId("GRANT-2025-001").currency("USD")
                .allocations(List.of())
                .items(List.of(SpendingItemRequest.builder()
                        .currency("USD").spendDate(LocalDate.of(2025, 4, 3)).build())) // amountRcy null
                .build();

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("ITEM_FIELDS_REQUIRED");
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    // --- publish ---

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
    void publish_returnsLeft_whenNotFound() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.empty());

        assertThat(spendingEventService.publish("e1").isLeft()).isTrue();
    }

    @Test
    void publish_returnsLeft_whenAlreadyPublished() {
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(event));

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.publish("e1");

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("SPENDING_EVENT_ALREADY_PUBLISHED");
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    // --- delete ---

    @Test
    void delete_returns204_forDraftEvent() {
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(event));

        assertThat(spendingEventService.delete("e1").isRight()).isTrue();
        verify(fundingEventRepository).delete(event);
    }

    @Test
    void delete_returnsLeft_whenNotFound() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.empty());

        assertThat(spendingEventService.delete("e1").isLeft()).isTrue();
        verify(fundingEventRepository, never()).delete(any());
    }

    @Test
    void delete_returnsLeft_whenPublished() {
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(event));

        assertThat(spendingEventService.delete("e1").isLeft()).isTrue();
        verify(fundingEventRepository, never()).delete(any());
    }

    // --- findByProjectIdAndFilter ---

    @Test
    void findByProjectIdAndFilter_delegatesToRepository() {
        Pageable pageable = PageRequest.of(0, 10);
        var page = new PageImpl<>(List.of(eventEntity(EventType.SPENDING, EventStatus.DRAFT)));
        when(fundingEventRepository.findByProjectIdAndFilter("p1", EventStatus.DRAFT, EventType.SPENDING, pageable))
                .thenReturn(page);

        assertThat(spendingEventService.findByProjectIdAndFilter(
                "p1", Optional.of(EventStatus.DRAFT), Optional.of(EventType.SPENDING), pageable))
                .isEqualTo(page);
    }

    @Test
    void findByProjectIdAndFilter_passesNulls_whenFiltersEmpty() {
        Pageable pageable = PageRequest.of(0, 10);
        var page = new PageImpl<FundingEventEntity>(List.of());
        when(fundingEventRepository.findByProjectIdAndFilter("p1", null, null, pageable)).thenReturn(page);

        assertThat(spendingEventService.findByProjectIdAndFilter("p1", Optional.empty(), Optional.empty(), pageable))
                .isEqualTo(page);
    }

    // --- additional create validation paths ---

    @Test
    void create_returnsLeft_whenProjectIdNull() {
        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .organisationId("org1")
                .eventType(EventType.SPENDING)
                .fundingId("GRANT-2025-001")
                .currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId(null)
                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                .milestone(MilestoneCreateRequest.builder().externalMilestoneId("MS-1").build())
                                .build()))
                        .build()))
                .build();

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("PROJECT_FIELDS_REQUIRED");
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenNewRootProjectMissingTotalAmount() {
        when(projectRepository.existsById(any())).thenReturn(false);

        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .organisationId("org1")
                .eventType(EventType.SPENDING)
                .fundingId("GRANT-2025-001")
                .currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId("PROJ-NEW")
                        .projectTitle("New Project")
                        .fundingId("GRANT-2025-001")
                        // totalAmount and currency absent, no subProject
                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                .milestone(MilestoneCreateRequest.builder().externalMilestoneId("MS-1").build())
                                .build()))
                        .build()))
                .build();

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("PROJECT_FIELDS_REQUIRED");
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenNewRootProjectMissingFundingId() {
        when(projectRepository.existsById(any())).thenReturn(false);

        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .organisationId("org1")
                .eventType(EventType.SPENDING)
                .fundingId("GRANT-2025-001")
                .currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId("PROJ-NEW")
                        .projectTitle("New Project")
                        .totalAmount(new BigDecimal("100000.00"))
                        .currency("USD")
                        // fundingId absent
                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                .milestone(MilestoneCreateRequest.builder().externalMilestoneId("MS-1").build())
                                .build()))
                        .build()))
                .build();

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("PROJECT_FIELDS_REQUIRED");
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    // --- sub-project paths ---

    @Test
    void create_successWithExistingSubProject() {
        // projectEntity() uses id("p1"); service resolves root by hash then sub by subId(parent.getId(), "WP-1")
        ProjectEntity rootProject = projectEntity();
        String subProjectUid = ProjectEntity.subId(rootProject.getId(), "WP-1");
        ProjectEntity subProject = ProjectEntity.builder()
                .id(subProjectUid).organisationId("org1").externalProjectId("WP-1")
                .projectTitle("Work Package 1").parentProject(rootProject).build();
        MilestoneEntity milestone = MilestoneEntity.builder()
                .id("m1").milestoneTitle("Milestone AB").milestoneAmount(new BigDecimal("50000.00"))
                .currency("USD").milestoneDate(LocalDate.of(2025, 6, 30)).project(subProject).build();

        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any()))
                .thenReturn(Optional.of(rootProject))   // root project lookup
                .thenReturn(Optional.of(subProject));   // sub-project lookup
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(any(), eq("MS-1"))).thenReturn(Optional.of(milestone));
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .organisationId("org1")
                .eventType(EventType.SPENDING)
                .fundingId("GRANT-2025-001")
                .currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId("PROJ-AB")
                        .subProject(SubProjectRequest.builder().subProjectId("WP-1").build())
                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                .milestone(MilestoneCreateRequest.builder().externalMilestoneId("MS-1").build())
                                .build()))
                        .build()))
                .build();

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isRight()).isTrue();
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_successWithNewSubProject() {
        ProjectEntity rootProject = projectEntity();

        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any()))
                .thenReturn(Optional.of(rootProject))  // root project lookup
                .thenReturn(Optional.empty());          // sub-project not found → create new
        when(projectRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(milestoneRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .organisationId("org1")
                .eventType(EventType.SPENDING)
                .fundingId("GRANT-2025-001")
                .currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId("PROJ-AB")
                        .subProject(SubProjectRequest.builder()
                                .subProjectId("WP-NEW")
                                .projectTitle("New Work Package")
                                .build())
                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                .milestone(MilestoneCreateRequest.builder()
                                        .milestoneTitle("Deliverable 1")
                                        .milestoneAmount(new BigDecimal("50000.00"))
                                        .currency("USD")
                                        .milestoneDate(LocalDate.of(2025, 9, 30))
                                        .build())
                                .build()))
                        .build()))
                .build();

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isRight()).isTrue();
        verify(projectRepository).saveAndFlush(argThat(p -> "WP-NEW".equals(p.getExternalProjectId())));
    }

    @Test
    void create_returnsLeft_whenSubProjectIdNull() {
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(projectEntity()));

        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .organisationId("org1")
                .eventType(EventType.SPENDING)
                .fundingId("GRANT-2025-001")
                .currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId("PROJ-AB")
                        .subProject(SubProjectRequest.builder().subProjectId(null).build())
                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                .milestone(MilestoneCreateRequest.builder().externalMilestoneId("MS-1").build())
                                .build()))
                        .build()))
                .build();

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("PROJECT_FIELDS_REQUIRED");
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenNewSubProjectMissingTitle() {
        ProjectEntity rootProject = projectEntity();

        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any()))
                .thenReturn(Optional.of(rootProject))  // root project lookup
                .thenReturn(Optional.empty());          // sub-project not found

        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .organisationId("org1")
                .eventType(EventType.SPENDING)
                .fundingId("GRANT-2025-001")
                .currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId("PROJ-AB")
                        .subProject(SubProjectRequest.builder()
                                .subProjectId("WP-NEW")
                                .projectTitle(null)
                                .build())
                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                .milestone(MilestoneCreateRequest.builder().externalMilestoneId("MS-1").build())
                                .build()))
                        .build()))
                .build();

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.create(request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("PROJECT_FIELDS_REQUIRED");
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    // --- update ---

    @Test
    void update_returnsLeft_whenNotFound() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.empty());

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.update("e1",
                requestWithExistingProject("PROJ-AB", "MS-1"));

        assertThat(result.isLeft()).isTrue();
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returnsLeft_whenPublished() {
        FundingEventEntity published = eventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(published));

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.update("e1",
                requestWithExistingProject("PROJ-AB", "MS-1"));

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("SPENDING_EVENT_ALREADY_PUBLISHED");
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_success_replacesAllocationsAndItems() {
        FundingEventEntity existing = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        ProjectEntity project = projectEntity();
        MilestoneEntity milestone = milestoneEntity("m1");
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(existing));
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(project));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(project.getId(), "MS-1")).thenReturn(Optional.of(milestone));
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.update("e1",
                requestWithExistingProject("PROJ-AB", "MS-1"));

        assertThat(result.isRight()).isTrue();
    }

    @Test
    void update_fundingEvent_populatesItems() {
        FundingEventEntity existing = eventEntity(EventType.FUNDING, EventStatus.DRAFT);
        ProjectEntity project = projectEntity();
        MilestoneEntity milestone = milestoneEntity("m1");
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(existing));
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(project));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(project.getId(), "MS-1")).thenReturn(Optional.of(milestone));
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        // FUNDING/REFUND items are the light variant: only amount_rcy + date + currency.
        SpendingItemRequest fundingItem = SpendingItemRequest.builder()
                .amountRcy(new BigDecimal("100.00"))
                .currency("USD")
                .spendDate(LocalDate.of(2025, 4, 3))
                .build();

        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder()
                .organisationId("org1")
                .eventType(EventType.FUNDING)
                .fundingId("GRANT-2025-001")
                .currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId("PROJ-AB")
                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                .milestone(MilestoneCreateRequest.builder().externalMilestoneId("MS-1").build())
                                .allocatedAmount(new BigDecimal("50000.00"))
                                .build()))
                        .build()))
                .items(List.of(fundingItem))
                .build();

        Either<ProblemDetail, FundingEventEntity> result = spendingEventService.update("e1", request);

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().getSpendingItems()).hasSize(1);
        assertThat(result.get().getSpendingItems().get(0).getAmountRcy()).isEqualByComparingTo("100.00");
    }

    // --- toView ---

    @Test
    void toView_mapsScalarFields() {
        FundingEventEntity event = FundingEventEntity.builder()
                .id("e1")
                .eventType(EventType.SPENDING)
                .status(EventStatus.PUBLISHED)
                .organisationId("org1")
                .fundingId("GRANT-001")
                .currency("EUR")
                .totalAmount(new BigDecimal("777.00"))
                .txHash("tx-abc")
                .fundingHash("funding-hash-xyz")
                .build();

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of());

        SpendingEventView view = spendingEventService.toView(event);

        assertThat(view.getEventId()).isEqualTo("e1");
        assertThat(view.getOrganisationId()).isEqualTo("org1");
        assertThat(view.getEventType()).isEqualTo(EventType.SPENDING);
        assertThat(view.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(view.getFundingId()).isEqualTo("GRANT-001");
        assertThat(view.getCurrency()).isEqualTo("EUR");
        assertThat(view.getTotalAmount()).isEqualByComparingTo("777.00");
        assertThat(view.getTxHash()).isEqualTo("tx-abc");
        assertThat(view.getFundingHash()).isEqualTo("funding-hash-xyz");
        assertThat(view.getProjectAllocations()).isEmpty();
        assertThat(view.getSpendingItems()).isEmpty();
    }

    @Test
    void toView_mapsProjectAllocations() {
        FundingEventEntity event = eventEntity(EventType.FUNDING, EventStatus.DRAFT);
        ProjectEntity project = projectEntity();
        MilestoneEntity milestone = milestoneEntity("m1");
        EventMilestoneAllocationEntity.Id allocId = new EventMilestoneAllocationEntity.Id("e1", "m1");
        EventMilestoneAllocationEntity alloc = EventMilestoneAllocationEntity.builder()
                .id(allocId).build();

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of(alloc));
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));

        SpendingEventView view = spendingEventService.toView(event);

        assertThat(view.getProjectAllocations()).hasSize(1);
        assertThat(view.getProjectAllocations().get(0).getProjectId()).isEqualTo("p1");
        assertThat(view.getProjectAllocations().get(0).getExternalProjectId()).isEqualTo("PROJ-AB");
    }

    @Test
    void toView_mapsSpendingItems() {
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        SpendingItemEntity item = itemEntity(event);

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of(item));
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of());

        SpendingEventView view = spendingEventService.toView(event);

        assertThat(view.getSpendingItems()).hasSize(1);
        assertThat(view.getSpendingItems().get(0).getCategory()).isEqualTo("Personnel");
    }

    // --- toPublishView ---

    @Test
    void toPublishView_mapsScalarFields() {
        FundingEventEntity event = FundingEventEntity.builder()
                .id("e1").eventType(EventType.SPENDING).status(EventStatus.PUBLISHED)
                .organisationId("org1").fundingId("GRANT-001").currency("USD")
                .totalAmount(new BigDecimal("300.00")).fundingHash("funding-hash").fundingEntity("CF")
                .build();
        event.setCreatedAt(LocalDateTime.of(2025, 6, 15, 10, 0));

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of());

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getEventId()).isEqualTo("e1");
        assertThat(view.getOrganisationId()).isEqualTo("org1");
        assertThat(view.getEventType()).isEqualTo(EventType.SPENDING);
        assertThat(view.getDate()).isEqualTo(LocalDate.of(2025, 6, 15));
        assertThat(view.getFundingId()).isEqualTo("GRANT-001");
        assertThat(view.getFundingHash()).isEqualTo("funding-hash");
        assertThat(view.getFundingEntity()).isEqualTo("CF");
        assertThat(view.getAmount()).isEqualByComparingTo("300.00");
        assertThat(view.getCurrency().getCustCode()).isEqualTo("USD");
        assertThat(view.getCurrency().getId()).isEqualTo("ISO_4217:USD");
        assertThat(view.getProjectAllocations()).isEmpty();
        assertThat(view.getItems()).isEmpty();
    }

    @Test
    void toPublishView_currencyAlreadyIsoFormatted_extractsCustCode() {
        FundingEventEntity event = FundingEventEntity.builder()
                .id("e1").eventType(EventType.SPENDING).status(EventStatus.DRAFT)
                .organisationId("org1").fundingId("GRANT-001").currency("ISO_4217:EUR")
                .totalAmount(BigDecimal.ZERO).build();
        event.setCreatedAt(LocalDateTime.of(2025, 6, 15, 10, 0));

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of());

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getCurrency().getId()).isEqualTo("ISO_4217:EUR");
        assertThat(view.getCurrency().getCustCode()).isEqualTo("EUR");
    }

    @Test
    void toPublishView_mapsSpendingItems() {
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        event.setCreatedAt(LocalDateTime.of(2025, 4, 3, 0, 0));
        SpendingItemEntity item = itemEntity(event);

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of(item));
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of());

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getItems()).hasSize(1);
        SpendingEventPublishView.SpendItem spendItem = view.getItems().get(0);
        assertThat(spendItem.getCategory()).isEqualTo("Personnel");
        assertThat(spendItem.getCurrency().getCustCode()).isEqualTo("USD");
        assertThat(spendItem.getCurrency().getId()).isEqualTo("ISO_4217:USD");
        assertThat(spendItem.getAmountFcy()).isEqualByComparingTo("100.00");
    }

    @Test
    void toPublishView_mapsProjectAllocations() {
        FundingEventEntity event = eventEntity(EventType.FUNDING, EventStatus.DRAFT);
        event.setCreatedAt(LocalDateTime.of(2025, 6, 1, 0, 0));
        MilestoneEntity milestone = milestoneEntity("m1");
        EventMilestoneAllocationEntity.Id allocId = new EventMilestoneAllocationEntity.Id("e1", "m1");
        EventMilestoneAllocationEntity alloc = EventMilestoneAllocationEntity.builder()
                .id(allocId).allocatedAmount(new BigDecimal("50000.00")).build();

        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of(alloc));
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));

        SpendingEventPublishView view = spendingEventService.toPublishView(event);

        assertThat(view.getProjectAllocations()).hasSize(1);
        SpendingEventPublishView.ProjectAllocation allocation = view.getProjectAllocations().get(0);
        assertThat(allocation.getExternalProjectId()).isEqualTo("PROJ-AB");
        assertThat(allocation.getProjectTitle()).isEqualTo("Project AB");
        assertThat(allocation.getSubProjectTitle()).isNull();
        assertThat(allocation.getMilestones()).hasSize(1);
        SpendingEventPublishView.Milestone ms = allocation.getMilestones().get(0);
        assertThat(ms.getMilestoneId()).isEqualTo("m1");
        assertThat(ms.getMilestoneTitle()).isEqualTo("Milestone AB");
        assertThat(ms.getAllocatedAmount()).isEqualByComparingTo("50000.00");
        assertThat(ms.getCurrency().getCustCode()).isEqualTo("USD");
        assertThat(ms.getCurrency().getId()).isEqualTo("ISO_4217:USD");
    }

    // -------------------------------------------------------------------------
    // View-returning API + org-access authorisation
    // -------------------------------------------------------------------------

    @Test
    void listEvents_returns401_whenUserCannotAccessOrg() {
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        PagedResponse<SpendingEventView> result = spendingEventService.listEvents(
                "org1", Optional.empty(), Optional.empty(), VIEW_PAGEABLE);

        assertThat(result.getError().orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(fundingEventRepository, never()).findByOrganisationIdAndFilter(any(), any(), any(), any());
    }

    @Test
    void listEvents_returnsPage_whenAuthorised() {
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(fundingEventRepository.findByOrganisationIdAndFilter("org1", null, null, VIEW_PAGEABLE))
                .thenReturn(new PageImpl<>(List.of()));

        PagedResponse<SpendingEventView> result = spendingEventService.listEvents(
                "org1", Optional.empty(), Optional.empty(), VIEW_PAGEABLE);

        assertThat(result.getError()).isEmpty();
    }

    @Test
    void listEventsByProject_returns404_whenProjectNotFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.empty());

        PagedResponse<SpendingEventView> result = spendingEventService.listEventsByProject(
                "p1", Optional.empty(), Optional.empty(), VIEW_PAGEABLE);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    @Test
    void listEventsByProject_returns401_whenUserCannotAccessOrg() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        PagedResponse<SpendingEventView> result = spendingEventService.listEventsByProject(
                "p1", Optional.empty(), Optional.empty(), VIEW_PAGEABLE);

        assertThat(result.getError().orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(fundingEventRepository, never()).findByProjectIdAndFilter(any(), any(), any(), any());
    }

    @Test
    void listEventsByProject_returnsPage_whenAuthorised() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(fundingEventRepository.findByProjectIdAndFilter("p1", null, null, VIEW_PAGEABLE))
                .thenReturn(new PageImpl<>(List.of()));

        PagedResponse<SpendingEventView> result = spendingEventService.listEventsByProject(
                "p1", Optional.empty(), Optional.empty(), VIEW_PAGEABLE);

        assertThat(result.getError()).isEmpty();
    }

    @Test
    void getEvent_returns404_whenNotFound() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.empty());

        SpendingEventView result = spendingEventService.getEvent("e1");

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
    }

    @Test
    void getEvent_returns401_whenUserCannotAccessOrg() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.SPENDING, EventStatus.DRAFT)));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        SpendingEventView result = spendingEventService.getEvent("e1");

        assertThat(result.getError().orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void getEvent_returnsView_whenAuthorised() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.SPENDING, EventStatus.DRAFT)));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of());

        SpendingEventView result = spendingEventService.getEvent("e1");

        assertThat(result.getError()).isEmpty();
        assertThat(result.getEventId()).isEqualTo("e1");
    }

    @Test
    void createEvent_returnsErrorView_whenCreateFails() {
        // externalProjectId null → create() fails validation with PROJECT_FIELDS_REQUIRED
        // before touching any repository.
        SpendingEventView result = spendingEventService.createEvent(SpendingEventCreateRequest.builder()
                .organisationId("org1").eventType(EventType.SPENDING).fundingId("GRANT-2025-001").currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId(null)
                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                .milestone(MilestoneCreateRequest.builder().externalMilestoneId("MS-1").build())
                                .build()))
                        .build()))
                .build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo("PROJECT_FIELDS_REQUIRED");
    }

    @Test
    void createEvent_returnsView_whenCreated() {
        ProjectEntity project = projectEntity();
        when(projectRepository.existsById(any())).thenReturn(true);
        when(projectRepository.findById(any())).thenReturn(Optional.of(project));
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId(project.getId(), "MS-1"))
                .thenReturn(Optional.of(milestoneEntity("m1")));
        when(fundingEventRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(spendingItemRepository.findByEvent_Id(any())).thenReturn(List.of());
        when(milestoneAllocationRepository.findById_EventId(any())).thenReturn(List.of());

        SpendingEventView result = spendingEventService.createEvent(requestWithExistingProject("PROJ-AB", "MS-1"));

        assertThat(result.getError()).isEmpty();
    }

    @Test
    void updateEvent_returns401_whenUserCannotAccessOrg() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.SPENDING, EventStatus.DRAFT)));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        SpendingEventView result = spendingEventService.updateEvent("e1", requestWithExistingProject("PROJ-AB", "MS-1"));

        assertThat(result.getError().orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateEvent_returnsErrorView_whenUpdateFails() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.SPENDING, EventStatus.PUBLISHED)));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);

        SpendingEventView result = spendingEventService.updateEvent("e1", requestWithExistingProject("PROJ-AB", "MS-1"));

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void publishEvent_returns401_whenUserCannotAccessOrg() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.SPENDING, EventStatus.DRAFT)));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        SpendingEventView result = spendingEventService.publishEvent("e1");

        assertThat(result.getError().orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(fundingEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void publishEvent_returnsView_whenAuthorisedAndPublished() {
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(event));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(fundingEventRepository.saveAndFlush(event)).thenReturn(event);
        when(spendingItemRepository.findByEvent_Id("e1")).thenReturn(List.of());
        when(milestoneAllocationRepository.findById_EventId("e1")).thenReturn(List.of());

        SpendingEventView result = spendingEventService.publishEvent("e1");

        assertThat(result.getError()).isEmpty();
        assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
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

        Optional<ProblemDetail> result = spendingEventService.deleteEvent("e1");

        assertThat(result).isEmpty();
        verify(fundingEventRepository).delete(event);
    }

    @Test
    void deleteEvent_returnsError_whenPublished() {
        when(fundingEventRepository.findById("e1")).thenReturn(Optional.of(eventEntity(EventType.SPENDING, EventStatus.PUBLISHED)));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);

        Optional<ProblemDetail> result = spendingEventService.deleteEvent("e1");

        assertThat(result.orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
        verify(fundingEventRepository, never()).delete(any());
    }

    // --- helpers ---

    private FundingEventEntity eventEntity(EventType type, EventStatus status) {
        return FundingEventEntity.builder()
                .id("e1")
                .eventType(type)
                .status(status)
                .organisationId("org1")
                .fundingId("GRANT-2025-001")
                .currency("USD")
                .totalAmount(BigDecimal.ZERO)
                .build();
    }

    private ProjectEntity projectEntity() {
        return ProjectEntity.builder()
                .id("p1")
                .organisationId("org1")
                .fundingId("GRANT-2025-001")
                .externalProjectId("PROJ-AB")
                .projectTitle("Project AB")
                .totalAmount(new BigDecimal("200000.00"))
                .currency("USD")
                .build();
    }

    private MilestoneEntity milestoneEntity(String id) {
        return MilestoneEntity.builder()
                .id(id)
                .milestoneTitle("Milestone AB")
                .milestoneAmount(new BigDecimal("50000.00"))
                .currency("USD")
                .milestoneDate(LocalDate.of(2025, 6, 30))
                .project(projectEntity())
                .build();
    }

    private SpendingItemEntity itemEntity(FundingEventEntity event) {
        return SpendingItemEntity.builder()
                .id("item-1")
                .category("Personnel")
                .vendor("Vendor AB")
                .amountFcy(new BigDecimal("100.00"))
                .currency("USD")
                .spendDate(LocalDate.of(2025, 4, 3))
                .event(event)
                .build();
    }

    private SpendingItemRequest itemRequest(BigDecimal amount) {
        return SpendingItemRequest.builder()
                .category("Personnel")
                .vendor("Vendor AB")
                .amountFcy(amount)
                .currency("USD")
                .spendDate(LocalDate.of(2025, 4, 3))
                .build();
    }

    private SpendingEventCreateRequest requestWithExistingProject(String projectId, String milestoneId) {
        return SpendingEventCreateRequest.builder()
                .organisationId("org1")
                .eventType(EventType.SPENDING)
                .fundingId("GRANT-2025-001")
                .currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId(projectId)
                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                .milestone(MilestoneCreateRequest.builder().externalMilestoneId(milestoneId).build())
                                .build()))
                        .build()))
                .build();
    }

    private SpendingEventCreateRequest requestWithNewProjectAndMilestone() {
        return SpendingEventCreateRequest.builder()
                .organisationId("org1")
                .eventType(EventType.SPENDING)
                .fundingId("GRANT-2025-001")
                .currency("USD")
                .allocations(List.of(EventProjectAllocationRequest.builder()
                        .externalProjectId("PROJ-NEW")
                        .projectTitle("New Project")
                        .fundingId("GRANT-2025-001")
                        .totalAmount(new BigDecimal("100000.00"))
                        .currency("USD")
                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                .milestone(MilestoneCreateRequest.builder()
                                        .milestoneTitle("New Milestone")
                                        .milestoneAmount(new BigDecimal("50000.00"))
                                        .currency("USD")
                                        .milestoneDate(LocalDate.of(2026, 1, 31))
                                        .build())
                                .build()))
                        .build()))
                .build();
    }

}
