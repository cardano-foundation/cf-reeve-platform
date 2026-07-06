package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.InjectMocks;
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

    @InjectMocks
    private ProjectService projectService;

    private static final Pageable PAGEABLE = PageRequest.of(0, 10);

    @BeforeEach
    void allowOrgAndEmptyChildren() {
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
    void create_conflict_whenAlreadyExists() {
        ProjectWithMilestonesCreateRequest request = createRequest();
        when(projectRepository.existsByOrganisationIdAndExternalProjectId("org1", "PROJ-AB")).thenReturn(true);

        ProjectView result = projectService.createWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_ALREADY_EXISTS);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_success() {
        ProjectWithMilestonesCreateRequest request = createRequest();
        ProjectEntity saved = projectEntity();
        when(projectRepository.existsByOrganisationIdAndExternalProjectId("org1", "PROJ-AB")).thenReturn(false);
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
        when(projectRepository.existsByOrganisationIdAndExternalProjectId("org1", "PROJ-AB")).thenReturn(false);
        when(projectRepository.saveAndFlush(any())).thenReturn(projectEntity());
        when(milestoneService.create(eq("p1"), any()))
                .thenReturn(Either.left(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST)));

        ProjectView result = projectService.createWithMilestones(request);

        assertThat(result.getError()).isPresent();
    }

    // --- createWithMilestones: sub-project tree ---

    @Test
    void createTree_success_projectWithSubProjectsEachWithMilestones() {
        when(projectRepository.existsByOrganisationIdAndExternalProjectId("org1", "PROJ-AB")).thenReturn(false);
        when(projectRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(projectRepository.existsById(any())).thenReturn(false);
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
        when(projectRepository.existsByOrganisationIdAndExternalProjectId("org1", "PROJ-AB")).thenReturn(false);
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
    void createTree_returns400_whenSubProjectTotalExceedsParent() {
        when(projectRepository.existsByOrganisationIdAndExternalProjectId("org1", "PROJ-AB")).thenReturn(false);
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
        when(allocationRepository.existsByMilestoneProjectIdAndEventStatus("p1", EventStatus.PUBLISHED)).thenReturn(true);

        ProjectView result = projectService.updateProject("p1", ProjectUpdateRequest.builder().projectTitle("New").build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
    }

    @Test
    void update_success() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(allocationRepository.existsByMilestoneProjectIdAndEventStatus("p1", EventStatus.PUBLISHED)).thenReturn(false);
        when(projectRepository.saveAndFlush(project)).thenReturn(project);

        ProjectView result = projectService.updateProject("p1", ProjectUpdateRequest.builder().projectTitle("New").build());

        assertThat(result.getError()).isEmpty();
        assertThat(result.getProjectId()).isEqualTo("p1");
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
        when(allocationRepository.existsByMilestoneProjectIdAndEventStatus("p1", EventStatus.PUBLISHED)).thenReturn(false);
        when(projectRepository.findById("parent1")).thenReturn(Optional.of(parent));
        when(projectRepository.saveAndFlush(project)).thenReturn(project);

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().parentProjectId("parent1").build());

        assertThat(result.getError()).isEmpty();
        assertThat(project.getParentProject()).isEqualTo(parent);
    }

    @Test
    void update_returns404_whenParentNotFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(allocationRepository.existsByMilestoneProjectIdAndEventStatus("p1", EventStatus.PUBLISHED)).thenReturn(false);
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
        when(allocationRepository.existsByMilestoneProjectIdAndEventStatus("p1", EventStatus.PUBLISHED)).thenReturn(false);
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
        when(allocationRepository.existsByMilestoneProjectIdAndEventStatus("p1", EventStatus.PUBLISHED)).thenReturn(false);

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
        when(allocationRepository.existsByMilestoneProjectIdAndEventStatus("p1", EventStatus.PUBLISHED)).thenReturn(false);
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
        when(allocationRepository.existsByMilestoneProjectIdAndEventStatus("p1", EventStatus.PUBLISHED)).thenReturn(false);
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
        when(allocationRepository.existsByMilestoneProjectIdAndEventStatus("p1", EventStatus.PUBLISHED)).thenReturn(false);
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
        when(allocationRepository.existsByMilestoneProjectIdAndEventStatus("p1", EventStatus.PUBLISHED)).thenReturn(false);
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
        when(allocationRepository.existsByMilestoneProjectIdAndEventStatus("p1", EventStatus.PUBLISHED)).thenReturn(false);

        ProjectView result = projectService.updateProject("p1",
                ProjectUpdateRequest.builder().totalAmount(new BigDecimal("-1")).build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_AMOUNT_INVALID);
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
