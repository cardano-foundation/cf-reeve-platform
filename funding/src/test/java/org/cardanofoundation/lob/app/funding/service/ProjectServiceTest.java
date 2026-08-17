package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
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

import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectTreeNodeRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private FundingProjectRepository projectRepository;
    @Mock
    private MilestoneService milestoneService;
    @Mock
    private SpendingEventService spendingEventService;
    @Mock
    private EventMilestoneAllocationRepository allocationRepository;
    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;
    @Mock
    private OrganisationPublicApiIF organisationPublicApi;
    @Mock
    private FundingCascadeDeleteService cascadeDeleteService;

    private ProjectService projectService;

    private static final Pageable PAGEABLE = PageRequest.of(0, 10);

    @BeforeEach
    void allowOrgAndEmptyChildren() {
        // Real structure service over the mocked repo/milestone service, so sub-project creation
        // rules are exercised for real while everything else stays stubbed.
        projectService = new ProjectService(projectRepository, milestoneService, spendingEventService,
                new ProjectStructureService(projectRepository, milestoneService),
                allocationRepository, keycloakSecurityHelper, organisationPublicApi, cascadeDeleteService);
        lenient().when(keycloakSecurityHelper.canUserAccessOrg(any())).thenReturn(true);
        lenient().when(milestoneService.findByProjectId(any())).thenReturn(List.of());
        lenient().when(projectRepository.findByParentProjectId(any(String.class))).thenReturn(List.of());
        lenient().when(spendingEventService.findByProjectIdAndFilter(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
    }

    private ProjectEntity projectEntity() {
        return ProjectEntity.builder().id("p1").organisationId("org1").fundingId("GRANT-2025-001")
                .externalProjectId("PROJ-AB").projectTitle("Project AB")
                .totalAmount(new BigDecimal("200000.00")).currency("USD").build();
    }

    // --- listProjects ---

    @Test
    void listProjects_unauthorized() {
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        PagedResponse<ProjectView> result = projectService.listProjects("org1", PAGEABLE);

        assertThat(result.getError().orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void listProjects_organisationNotFound() {
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.empty());

        PagedResponse<ProjectView> result = projectService.listProjects("org1", PAGEABLE);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo("ORGANISATION_NOT_FOUND");
    }

    @Test
    void listProjects_success() {
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(mock(Organisation.class)));
        when(projectRepository.findByOrganisationId("org1", PAGEABLE)).thenReturn(new PageImpl<>(List.of(projectEntity())));

        PagedResponse<ProjectView> result = projectService.listProjects("org1", PAGEABLE);

        assertThat(result.getError()).isEmpty();
        assertThat(result.getContent()).hasSize(1);
    }

    // --- getProject ---

    @Test
    void getProject_notFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.empty());

        ProjectView result = projectService.getProject("p1");

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    @Test
    void getProject_unauthorized() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        ProjectView result = projectService.getProject("p1");

        assertThat(result.getError().orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void getProject_success() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));

        ProjectView result = projectService.getProject("p1");

        assertThat(result.getError()).isEmpty();
        assertThat(result.getProjectId()).isEqualTo("p1");
    }

    @Test
    void getProject_includesAssociatedEvents() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        FundingEventEntity event = FundingEventEntity.builder().id("e1").organisationId("org1").build();
        SpendingEventView eventView = SpendingEventView.builder().eventId("e1").build();
        when(spendingEventService.findByProjectIdAndFilter(eq("p1"), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(event)));
        when(spendingEventService.toView(event)).thenReturn(eventView);

        ProjectView result = projectService.getProject("p1");

        assertThat(result.getError()).isEmpty();
        assertThat(result.getEvents()).hasSize(1);
        assertThat(result.getEvents().get(0).getEventId()).isEqualTo("e1");
    }

    // --- createWithMilestones ---

    @Test
    void create_conflict_whenFundingIdAlreadyUsed() {
        ProjectWithMilestonesCreateRequest request = createRequest(); // fundingId GRANT-2025-001
        when(projectRepository.existsByOrganisationIdAndFundingId("org1", "GRANT-2025-001")).thenReturn(true);

        ProjectView result = projectService.createWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_FUNDING_ID_ALREADY_USED);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_conflict_whenProjectTitleAlreadyExists() {
        ProjectWithMilestonesCreateRequest request = createRequest(); // title "Project AB"
        when(projectRepository.existsByOrganisationIdAndProjectTitleAndParentProjectIsNull("org1", "Project AB")).thenReturn(true);

        ProjectView result = projectService.createWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_asSubProject_conflict_whenTitleExistsUnderParent() {
        ProjectEntity parent = ProjectEntity.builder().id("parent1").organisationId("org1")
                .totalAmount(new BigDecimal("200000.00")).currency("USD").build();
        when(projectRepository.findById("parent1")).thenReturn(Optional.of(parent));
        when(milestoneService.hasMilestones("parent1")).thenReturn(false);
        when(projectRepository.existsByParentProjectIdAndProjectTitle("parent1", "Work Package 1")).thenReturn(true);

        ProjectView result = projectService.createWithMilestones(ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("WP-1").projectTitle("Work Package 1")
                .totalAmount(new BigDecimal("100000.00")).currency("USD").parentProjectId("parent1")
                .build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_success() {
        ProjectWithMilestonesCreateRequest request = createRequest();
        ProjectEntity saved = projectEntity();
        when(projectRepository.saveAndFlush(any())).thenReturn(saved);

        ProjectView result = projectService.createWithMilestones(request);

        assertThat(result.getError()).isEmpty();
        assertThat(result.getProjectId()).isEqualTo("p1");
    }

    @Test
    void create_returnsError_whenMilestoneFails() {
        MilestoneCreateRequest milestoneReq = MilestoneCreateRequest.builder().milestoneTitle("MS").build();
        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("PROJ-AB").projectTitle("Project AB")
                .fundingId("GRANT-2025-001").totalAmount(new BigDecimal("200000.00")).currency("USD")
                .milestones(List.of(milestoneReq)).build();
        when(projectRepository.saveAndFlush(any())).thenReturn(projectEntity());
        when(milestoneService.create(eq("p1"), any()))
                .thenReturn(Either.left(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST)));

        ProjectView result = projectService.createWithMilestones(request);

        assertThat(result.getError()).isPresent();
    }

    // --- createWithMilestones: sub-project tree ---

    @Test
    void createTree_success_projectWithSubProjectsEachWithMilestones() {
        when(projectRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(milestoneService.create(any(), any())).thenReturn(Either.right(mock(MilestoneEntity.class)));

        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("PROJ-AB").projectTitle("Root")
                .totalAmount(new BigDecimal("200000.00")).currency("USD")
                .milestones(List.of()) // root has sub-projects, not milestones
                .subProjects(List.of(
                        node("WP-1", new BigDecimal("100000.00"), List.of(milestoneReq()), List.of()),
                        node("WP-2", new BigDecimal("80000.00"), List.of(milestoneReq()), List.of())))
                .build();

        ProjectView result = projectService.createWithMilestones(request);

        assertThat(result.getError()).isEmpty();
        verify(projectRepository, times(3)).saveAndFlush(any());   // root + 2 sub-projects
        verify(milestoneService, times(2)).create(any(), any());   // one milestone per sub-project
    }

    @Test
    void createTree_returns400_whenRootHasBothMilestonesAndSubProjects() {
        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("PROJ-AB").projectTitle("Root")
                .totalAmount(new BigDecimal("200000.00")).currency("USD")
                .milestones(List.of(milestoneReq()))
                .subProjects(List.of(node("WP-1", new BigDecimal("100000.00"), List.of(milestoneReq()), List.of())))
                .build();

        ProjectView result = projectService.createWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SUBPROJECT_NOT_ALLOWED_WITH_MILESTONES);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void createTree_returns400_whenSubProjectNodeHasBothMilestonesAndSubProjects() {
        when(projectRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        ProjectTreeNodeRequest badNode = node("WP-1", new BigDecimal("100000.00"),
                List.of(milestoneReq()), List.of(node("WP-1-A", new BigDecimal("10000.00"), List.of(milestoneReq()), List.of())));
        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("PROJ-AB").projectTitle("Root")
                .totalAmount(new BigDecimal("200000.00")).currency("USD")
                .milestones(List.of()).subProjects(List.of(badNode)).build();

        ProjectView result = projectService.createWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SUBPROJECT_NOT_ALLOWED_WITH_MILESTONES);
        verify(projectRepository, times(1)).saveAndFlush(any()); // only the root before the node fails
    }

    @Test
    void createTree_returns409_whenSiblingSubProjectsShareTitle() {
        when(projectRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("PROJ-AB").projectTitle("Root")
                .totalAmount(new BigDecimal("200000.00")).currency("USD")
                .milestones(List.of())
                .subProjects(List.of(
                        ProjectTreeNodeRequest.builder().externalProjectId("WP-1").projectTitle("Shared Package")
                                .totalAmount(new BigDecimal("50000.00")).currency("USD")
                                .milestones(List.of(milestoneReq())).subProjects(List.of()).build(),
                        ProjectTreeNodeRequest.builder().externalProjectId("WP-2").projectTitle("Shared Package")
                                .totalAmount(new BigDecimal("50000.00")).currency("USD")
                                .milestones(List.of(milestoneReq())).subProjects(List.of()).build()))
                .build();

        ProjectView result = projectService.createWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS);
        verify(projectRepository, times(1)).saveAndFlush(any()); // only the root, before the sibling clash
    }

    @Test
    void createTree_returns400_whenSubProjectTotalExceedsParent() {
        when(projectRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("PROJ-AB").projectTitle("Root")
                .totalAmount(new BigDecimal("200000.00")).currency("USD")
                .milestones(List.of())
                .subProjects(List.of(node("WP-1", new BigDecimal("250000.00"), List.of(milestoneReq()), List.of())))
                .build();

        ProjectView result = projectService.createWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SUBPROJECT_AMOUNT_EXCEEDS_PARENT);
    }

    // --- createWithMilestones: create directly under an existing parent ---

    @Test
    void create_asSubProject_success_attachesToParentWithSubId() {
        ProjectEntity parent = ProjectEntity.builder().id("parent1").organisationId("org1")
                .totalAmount(new BigDecimal("200000.00")).currency("USD").build();
        when(projectRepository.findById("parent1")).thenReturn(Optional.of(parent));
        when(milestoneService.hasMilestones("parent1")).thenReturn(false);
        when(projectRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        ProjectView result = projectService.createWithMilestones(ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("WP-1").projectTitle("Work Package 1")
                .totalAmount(new BigDecimal("100000.00")).currency("USD").parentProjectId("parent1")
                .build());

        assertThat(result.getError()).isEmpty();
        assertThat(result.getParentProjectId()).isEqualTo("parent1");
        // The sub-project's deterministic id is derived from (parentId, projectTitle) — not externalProjectId.
        assertThat(result.getProjectId()).isEqualTo(ProjectEntity.subId("parent1", "Work Package 1"));
    }

    @Test
    void create_asSubProject_returns404_whenParentNotFound() {
        when(projectRepository.findById("missing")).thenReturn(Optional.empty());

        ProjectView result = projectService.createWithMilestones(ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("WP-1").projectTitle("WP")
                .totalAmount(new BigDecimal("100000.00")).currency("USD").parentProjectId("missing")
                .build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PARENT_PROJECT_NOT_FOUND);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_asSubProject_returns400_whenParentHasMilestones() {
        ProjectEntity parent = ProjectEntity.builder().id("parent1").organisationId("org1")
                .totalAmount(new BigDecimal("200000.00")).currency("USD").build();
        when(projectRepository.findById("parent1")).thenReturn(Optional.of(parent));
        when(milestoneService.hasMilestones("parent1")).thenReturn(true);

        ProjectView result = projectService.createWithMilestones(ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("WP-1").projectTitle("WP")
                .totalAmount(new BigDecimal("100000.00")).currency("USD").parentProjectId("parent1")
                .build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SUBPROJECT_NOT_ALLOWED_WITH_MILESTONES);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_asSubProject_returns400_whenAmountExceedsParent() {
        ProjectEntity parent = ProjectEntity.builder().id("parent1").organisationId("org1")
                .totalAmount(new BigDecimal("200000.00")).currency("USD").build();
        when(projectRepository.findById("parent1")).thenReturn(Optional.of(parent));
        when(milestoneService.hasMilestones("parent1")).thenReturn(false);

        ProjectView result = projectService.createWithMilestones(ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("WP-1").projectTitle("WP")
                .totalAmount(new BigDecimal("250000.00")).currency("USD").parentProjectId("parent1")
                .build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SUBPROJECT_AMOUNT_EXCEEDS_PARENT);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    // --- calculated spentAmount aggregation ---

    @Test
    void toView_aggregatesSpentAmountFromMilestones() {
        ProjectEntity project = projectEntity(); // p1, no sub-projects (lenient empty)
        MilestoneEntity milestone = MilestoneEntity.builder().id("m1").build();
        MilestoneView milestoneView = MilestoneView.builder().milestoneId("m1")
                .spentAmount(new BigDecimal("12000.00")).build();
        when(milestoneService.findByProjectId("p1")).thenReturn(List.of(milestone));
        when(milestoneService.toView(milestone)).thenReturn(milestoneView);

        ProjectView result = projectService.toView(project);

        assertThat(result.getSpentAmount()).isEqualByComparingTo("12000.00");
    }

    private ProjectTreeNodeRequest node(String extId, BigDecimal total,
            List<MilestoneCreateRequest> milestones, List<ProjectTreeNodeRequest> subProjects) {
        return ProjectTreeNodeRequest.builder()
                .externalProjectId(extId).projectTitle(extId).totalAmount(total).currency("USD")
                .milestones(milestones).subProjects(subProjects).build();
    }

    private MilestoneCreateRequest milestoneReq() {
        return MilestoneCreateRequest.builder().milestoneTitle("MS").milestoneAmount(new BigDecimal("50000.00"))
                .currency("USD").milestoneDate(LocalDate.now().plusYears(1)).build();
    }

    // --- updateProject ---

    @Test
    void update_notFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.empty());

        ProjectView result = projectService.updateProject("p1", ProjectUpdateRequest.builder().projectTitle("New").build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    @Test
    void update_conflict_whenLinkedToPublishedEvent() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(true);

        ProjectView result = projectService.updateProject("p1", ProjectUpdateRequest.builder().projectTitle("New").build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
    }

    @Test
    void update_success() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(projectRepository.saveAndFlush(project)).thenReturn(project);

        ProjectView result = projectService.updateProject("p1", ProjectUpdateRequest.builder().currency("EUR").build());

        assertThat(result.getError()).isEmpty();
        assertThat(result.getProjectId()).isEqualTo("p1");
        assertThat(project.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void update_success_whenProjectTitleResentUnchanged() {
        // Sending the same (unchanged) title back is not a "change" — it's a no-op, not rejected.
        ProjectEntity project = projectEntity(); // title "Project AB"
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(projectRepository.saveAndFlush(project)).thenReturn(project);

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().projectTitle("Project AB").build());

        assertThat(result.getError()).isEmpty();
    }

    @Test
    void update_conflict_whenDescendantSubProjectHasPublishedEvent() {
        // p1 has a sub-project sub1 whose milestone is tied to a published event → editing p1 (an
        // ancestor) is locked even though p1 itself owns no published milestone.
        ProjectEntity sub = ProjectEntity.builder().id("sub1").organisationId("org1").build();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of(sub));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(
                argThat(ids -> ids.contains("sub1")), eq(EventStatus.PUBLISHED))).thenReturn(true);

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().projectTitle("New").build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returns400_whenProjectTitleChanged() {
        // projectTitle is immutable — the project's id is derived from it. Attempting to change it is
        // rejected outright, regardless of whether the new title would itself conflict with anything.
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity())); // title "Project AB"
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().projectTitle("Renamed").build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_TITLE_IMMUTABLE);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    // --- deleteProject ---

    @Test
    void delete_notFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.empty());

        Optional<ProblemDetail> result = projectService.deleteProject("p1");

        assertThat(result.orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    @Test
    void delete_returns401_whenUserCannotAccessOrg() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        Optional<ProblemDetail> result = projectService.deleteProject("p1");

        assertThat(result.orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(cascadeDeleteService, never()).deleteProjectSubtree(any());
    }

    @Test
    void delete_propagatesConflict_fromCascade() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        ProblemDetail conflict = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        conflict.setTitle(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
        when(cascadeDeleteService.deleteProjectSubtree(project)).thenReturn(Optional.of(conflict));

        Optional<ProblemDetail> result = projectService.deleteProject("p1");

        assertThat(result.orElseThrow().getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void delete_delegatesToCascade_whenAuthorised() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(cascadeDeleteService.deleteProjectSubtree(project)).thenReturn(Optional.empty());

        Optional<ProblemDetail> result = projectService.deleteProject("p1");

        assertThat(result).isEmpty();
        verify(cascadeDeleteService).deleteProjectSubtree(project);
    }

    // --- assign parent (attach as sub-project) ---

    @Test
    void update_assignsParent_whenValid() {
        ProjectEntity project = projectEntity();
        ProjectEntity parent = ProjectEntity.builder().id("parent1").organisationId("org1")
                .externalProjectId("PROJ-PARENT").projectTitle("Parent").totalAmount(new BigDecimal("500000.00")).currency("USD").build();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(projectRepository.findById("parent1")).thenReturn(Optional.of(parent));
        when(projectRepository.saveAndFlush(project)).thenReturn(project);

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().parentProjectId("parent1").build());

        assertThat(result.getError()).isEmpty();
        assertThat(project.getParentProject()).isEqualTo(parent);
    }

    @Test
    void update_conflict_whenReparentedUnderParentWithSameTitledSubProject() {
        // Moving p1 ("Project AB") under parent1 collides with an existing sub-project of the same title.
        ProjectEntity project = projectEntity(); // p1, title "Project AB"
        ProjectEntity parent = ProjectEntity.builder().id("parent1").organisationId("org1")
                .externalProjectId("PROJ-PARENT").projectTitle("Parent").totalAmount(new BigDecimal("500000.00")).currency("USD").build();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(projectRepository.findById("parent1")).thenReturn(Optional.of(parent));
        when(projectRepository.existsByParentProjectIdAndProjectTitleAndIdNot("parent1", "Project AB", "p1")).thenReturn(true);

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().parentProjectId("parent1").build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returns404_whenParentNotFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(projectRepository.findById("missing")).thenReturn(Optional.empty());

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().parentProjectId("missing").build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PARENT_PROJECT_NOT_FOUND);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returns400_whenParentInDifferentOrg() {
        ProjectEntity parent = ProjectEntity.builder().id("parent1").organisationId("org2")
                .externalProjectId("PROJ-PARENT").projectTitle("Parent").build();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(projectRepository.findById("parent1")).thenReturn(Optional.of(parent));

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().parentProjectId("parent1").build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PARENT_PROJECT_ORG_MISMATCH);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returns400_whenParentIsSelf() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().parentProjectId("p1").build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_CIRCULAR_DEPENDENCY);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returns400_whenParentIsDescendant() {
        ProjectEntity project = projectEntity(); // p1
        // candidate parent is a child of p1 → attaching p1 under it would form a cycle
        ProjectEntity descendant = ProjectEntity.builder().id("child1").organisationId("org1")
                .externalProjectId("PROJ-CHILD").projectTitle("Child").parentProject(project).build();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(projectRepository.findById("child1")).thenReturn(Optional.of(descendant));

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().parentProjectId("child1").build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_CIRCULAR_DEPENDENCY);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returns400_whenSubProjectTotalExceedsParent() {
        ProjectEntity project = ProjectEntity.builder().id("p1").organisationId("org1").externalProjectId("PROJ-AB")
                .projectTitle("Child").totalAmount(new BigDecimal("600000.00")).currency("USD").build();
        ProjectEntity parent = ProjectEntity.builder().id("parent1").organisationId("org1").externalProjectId("PROJ-PARENT")
                .projectTitle("Parent").totalAmount(new BigDecimal("500000.00")).currency("USD").build();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(projectRepository.findById("parent1")).thenReturn(Optional.of(parent));

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().parentProjectId("parent1").build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SUBPROJECT_AMOUNT_EXCEEDS_PARENT);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returns400_whenSubProjectsCumulativeTotalExceedsParent() {
        ProjectEntity project = ProjectEntity.builder().id("p1").organisationId("org1").externalProjectId("PROJ-AB")
                .projectTitle("Child").totalAmount(new BigDecimal("300000.00")).currency("USD").build();
        ProjectEntity parent = ProjectEntity.builder().id("parent1").organisationId("org1").externalProjectId("PROJ-PARENT")
                .projectTitle("Parent").totalAmount(new BigDecimal("500000.00")).currency("USD").build();
        ProjectEntity existingChild = ProjectEntity.builder().id("child-x").organisationId("org1").externalProjectId("PROJ-X")
                .projectTitle("Existing").totalAmount(new BigDecimal("300000.00")).currency("USD").parentProject(parent).build();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(projectRepository.findById("parent1")).thenReturn(Optional.of(parent));
        when(projectRepository.findByParentProjectId("parent1")).thenReturn(List.of(existingChild));

        // child 300000 fits under parent 500000, but 300000 existing + 300000 = 600000 exceeds it
        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().parentProjectId("parent1").build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SUBPROJECT_TOTAL_EXCEEDS_PARENT);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returns400_whenParentHasMilestones() {
        ProjectEntity parent = ProjectEntity.builder().id("parent1").organisationId("org1").externalProjectId("PROJ-PARENT")
                .projectTitle("Parent").totalAmount(new BigDecimal("500000.00")).currency("USD").build();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(projectRepository.findById("parent1")).thenReturn(Optional.of(parent));
        when(milestoneService.hasMilestones("parent1")).thenReturn(true);

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().parentProjectId("parent1").build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SUBPROJECT_NOT_ALLOWED_WITH_MILESTONES);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    // --- listSubProjects ---

    @Test
    void listSubProjects_returns404_whenParentNotFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.empty());

        PagedResponse<ProjectView> result = projectService.listSubProjects("p1", PAGEABLE);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    @Test
    void listSubProjects_returns401_whenUserCannotAccessOrg() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        PagedResponse<ProjectView> result = projectService.listSubProjects("p1", PAGEABLE);

        assertThat(result.getError().orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(projectRepository, never()).findByParentProjectId("p1", PAGEABLE);
    }

    @Test
    void listSubProjects_returnsPage_whenAuthorised() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(projectRepository.findByParentProjectId("p1", PAGEABLE))
                .thenReturn(new PageImpl<>(List.of(projectEntity())));

        PagedResponse<ProjectView> result = projectService.listSubProjects("p1", PAGEABLE);

        assertThat(result.getError()).isEmpty();
        assertThat(result.getContent()).hasSize(1);
    }

    // --- amount validation ---

    @Test
    void create_returns400_whenTotalAmountNotPositive() {
        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("PROJ-AB").projectTitle("Project AB")
                .fundingId("GRANT-2025-001").totalAmount(BigDecimal.ZERO).currency("USD").milestones(List.of()).build();

        ProjectView result = projectService.createWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_AMOUNT_INVALID);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returns400_whenTotalAmountNotPositive() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().totalAmount(new BigDecimal("-1")).build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_AMOUNT_INVALID);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returns400_whenNewTotalBelowMilestonesTotal() {
        // Milestones already claim 150000; shrinking the project to 100000 would leave them uncovered.
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(milestoneService.findByProjectId("p1")).thenReturn(List.of(
                MilestoneEntity.builder().id("m1").milestoneAmount(new BigDecimal("150000.00")).build()));

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().totalAmount(new BigDecimal("100000.00")).build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_AMOUNT_BELOW_MILESTONES);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returns400_whenNewTotalBelowSubProjectsTotal() {
        // Sub-projects already claim 150000; the parent cannot shrink below that.
        ProjectEntity subProject = ProjectEntity.builder().id("sub1").organisationId("org1")
                .totalAmount(new BigDecimal("150000.00")).build();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of(subProject));

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().totalAmount(new BigDecimal("100000.00")).build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_AMOUNT_BELOW_SUBPROJECTS);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returns400_whenSubProjectNewTotalExceedsItsParent() {
        // p1 is a sub-project of parent1 (total 200000); growing p1 to 250000 no longer fits.
        ProjectEntity parent = ProjectEntity.builder().id("parent1").organisationId("org1")
                .totalAmount(new BigDecimal("200000.00")).currency("USD").build();
        ProjectEntity project = ProjectEntity.builder().id("p1").organisationId("org1").externalProjectId("PROJ-AB")
                .projectTitle("Child").totalAmount(new BigDecimal("100000.00")).currency("USD")
                .parentProject(parent).build();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().totalAmount(new BigDecimal("250000.00")).build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SUBPROJECT_AMOUNT_EXCEEDS_PARENT);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    // --- internal delegations ---

    @Test
    void findById_delegatesToRepository() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        assertThat(projectService.findById("p1")).contains(project);
    }

    @Test
    void existsByOrganisationIdAndExternalProjectId_delegatesToRepository() {
        when(projectRepository.existsByOrganisationIdAndExternalProjectId("org1", "PROJ-AB")).thenReturn(true);

        assertThat(projectService.existsByOrganisationIdAndExternalProjectId("org1", "PROJ-AB")).isTrue();
    }

    private ProjectWithMilestonesCreateRequest createRequest() {
        return ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("PROJ-AB").projectTitle("Project AB")
                .fundingId("GRANT-2025-001").totalAmount(new BigDecimal("200000.00")).currency("USD")
                .milestones(List.of()).build();
    }

}
