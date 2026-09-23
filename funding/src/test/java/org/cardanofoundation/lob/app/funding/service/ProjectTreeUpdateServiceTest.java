package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectTreeNodeRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class ProjectTreeUpdateServiceTest {

    @Mock private FundingProjectRepository projectRepository;
    @Mock private MilestoneRepository milestoneRepository;
    @Mock private MilestoneService milestoneService;
    @Mock private ProjectService projectService;
    @Mock private ProjectStructureService projectStructureService;
    @Mock private EventMilestoneAllocationRepository allocationRepository;
    @Mock private FundingCascadeDeleteService cascadeDeleteService;
    @Mock private KeycloakSecurityHelper keycloakSecurityHelper;

    private ProjectTreeUpdateService service;

    @BeforeEach
    void setUp() {
        service = new ProjectTreeUpdateService(projectRepository, milestoneRepository, milestoneService,
                projectService, projectStructureService, allocationRepository, cascadeDeleteService, keycloakSecurityHelper);
        lenient().when(keycloakSecurityHelper.canUserAccessOrg(anyString())).thenReturn(true);
        lenient().when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(false);
        lenient().when(projectService.toView(any())).thenReturn(ProjectView.builder().projectId("root").build());
        // applyExistingMilestone delegates the actual field mutation to milestoneService.applyChanges —
        // mocked here, so it's a no-op unless told to behave like the real implementation.
        lenient().doAnswer(invocation -> {
            MilestoneEntity milestone = invocation.getArgument(0);
            MilestoneUpdateRequest request = invocation.getArgument(1);
            boolean titleChanging = invocation.getArgument(2);
            if (titleChanging) {
                milestone.setMilestoneTitle(request.getMilestoneTitle());
            }
            if (request.getDescription() != null) {
                milestone.setDescription(request.getDescription());
            }
            if (request.getMilestoneAmount() != null) {
                milestone.setMilestoneAmount(request.getMilestoneAmount());
            }
            if (request.getCurrency() != null) {
                milestone.setCurrency(request.getCurrency());
            }
            if (request.getMilestoneDate() != null) {
                milestone.setMilestoneDate(request.getMilestoneDate());
            }
            return null;
        }).when(milestoneService).applyChanges(any(), any(), anyBoolean());
    }

    private ProjectEntity root(BigDecimal total) {
        return ProjectEntity.builder().id("root").organisationId("org1").proId("PRJ-1000")
                .projectTitle("Updated Project Name").totalAmount(total).currency("USD").build();
    }

    private ProjectEntity subProject(ProjectEntity parent, String id, String proId, BigDecimal total) {
        return ProjectEntity.builder().id(id).organisationId("org1").proId(proId)
                .projectTitle(proId).totalAmount(total).currency("USD").parentProject(parent).build();
    }

    @Test
    void update_returns404_whenRootNotFound() {
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.empty());

        ProjectView result = service.updateWithMilestones(request(null));

        assertThat(result.getError().orElseThrow().getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void update_blocksEverything_whenAnyPublishedEventExistsAnywhereInSubtree() {
        ProjectEntity root = root(new BigDecimal("250000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(projectRepository.findByParentProjectId("root")).thenReturn(List.of());
        when(allocationRepository.existsByMilestoneProjectIdInAndEventStatus(any(), eq(EventStatus.PUBLISHED))).thenReturn(true);

        ProjectView result = service.updateWithMilestones(request(new BigDecimal("100000")));

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_shrinksParentAndSubProjectsTogether_whenTheWholeTreeStaysConsistent() {
        // The core scenario this service exists for: root 705,702.86 -> 250,000, WITH both sub-projects
        // shrinking to fit in the same call — impossible via the narrow single-field endpoints since
        // shrinking either one first would (correctly) reject against the other's still-old total.
        ProjectEntity root = root(new BigDecimal("705702.86"));
        ProjectEntity sub1 = subProject(root, "sub1", "PRJ-1000-1", new BigDecimal("286728.71"));
        ProjectEntity sub2 = subProject(root, "sub2", "PRJ-1000-2", new BigDecimal("418974.15"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(projectRepository.findByParentProjectId("root")).thenReturn(List.of(sub1, sub2));
        when(projectRepository.findByParentProjectIdAndProId("root", "PRJ-1000-1")).thenReturn(Optional.of(sub1));
        when(projectRepository.findByParentProjectIdAndProId("root", "PRJ-1000-2")).thenReturn(Optional.of(sub2));
        when(milestoneService.findByProjectId("root")).thenReturn(List.of());
        when(milestoneService.findByProjectId("sub1")).thenReturn(List.of());
        when(milestoneService.findByProjectId("sub2")).thenReturn(List.of());
        when(projectRepository.findByParentProjectId("sub1")).thenReturn(List.of());
        when(projectRepository.findByParentProjectId("sub2")).thenReturn(List.of());
        when(projectRepository.findById("root")).thenReturn(Optional.of(root));
        when(projectRepository.findById("sub1")).thenReturn(Optional.of(sub1));
        when(projectRepository.findById("sub2")).thenReturn(Optional.of(sub2));

        ProjectWithMilestonesCreateRequest request = request(new BigDecimal("100000"));
        request.setSubProjects(List.of(
                ProjectTreeNodeRequest.builder().externalProjectId("x").proId("PRJ-1000-1").projectTitle("PRJ-1000-1")
                        .totalAmount(new BigDecimal("40000")).build(),
                ProjectTreeNodeRequest.builder().externalProjectId("x").proId("PRJ-1000-2").projectTitle("PRJ-1000-2")
                        .totalAmount(new BigDecimal("60000")).build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError()).isEmpty();
        assertThat(root.getTotalAmount()).isEqualByComparingTo("100000");
        assertThat(sub1.getTotalAmount()).isEqualByComparingTo("40000");
        assertThat(sub2.getTotalAmount()).isEqualByComparingTo("60000");
        verify(cascadeDeleteService, never()).markContainedEventsAsErrorOrBlock(any());
    }

    @Test
    void update_rejectsAndRollsBackWholeTree_whenStillInconsistentAfterApplyingEveryValue() {
        // Root shrinks to 100,000 but only one of the two sub-projects is resized down — the other still
        // claims its old 418,974.15, so root's new total doesn't cover its children even after every
        // value in the request has been applied, and the whole update is rejected.
        ProjectEntity root = root(new BigDecimal("705702.86"));
        ProjectEntity sub1 = subProject(root, "sub1", "PRJ-1000-1", new BigDecimal("286728.71"));
        ProjectEntity sub2 = subProject(root, "sub2", "PRJ-1000-2", new BigDecimal("418974.15"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(projectRepository.findByParentProjectId("root")).thenReturn(List.of(sub1, sub2));
        when(projectRepository.findByParentProjectIdAndProId("root", "PRJ-1000-1")).thenReturn(Optional.of(sub1));
        when(projectRepository.findById("root")).thenReturn(Optional.of(root));
        // sub2 is left untouched by the request entirely.

        ProjectWithMilestonesCreateRequest request = request(new BigDecimal("100000"));
        request.setSubProjects(List.of(
                ProjectTreeNodeRequest.builder().externalProjectId("x").proId("PRJ-1000-1").projectTitle("PRJ-1000-1")
                        .totalAmount(new BigDecimal("50000")).build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_AMOUNT_BELOW_SUBPROJECTS);
        verify(projectRepository, never()).findById("sub1");
    }

    @Test
    void update_flagsShrunkMilestoneEventsAsError_ratherThanBlocking() {
        ProjectEntity root = root(new BigDecimal("200000"));
        MilestoneEntity milestone = MilestoneEntity.builder().id("m1").proId("PRJ-1000-M1")
                .milestoneTitle("Milestone 1").milestoneAmount(new BigDecimal("150000")).currency("USD").project(root).build();
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(projectRepository.findByParentProjectId("root")).thenReturn(List.of());
        when(milestoneRepository.findByProjectIdAndProId("root", "PRJ-1000-M1")).thenReturn(Optional.of(milestone));
        when(milestoneService.findByProjectId("root")).thenReturn(List.of(milestone));
        when(projectRepository.findById("root")).thenReturn(Optional.of(root));
        when(milestoneService.needsErrorFlagging(eq("m1"), any())).thenReturn(true);
        when(cascadeDeleteService.markContainedEventsAsErrorOrBlock(Set.of("m1"))).thenReturn(Optional.empty());

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setMilestones(List.of(MilestoneCreateRequest.builder()
                .proId("PRJ-1000-M1").milestoneTitle("Milestone 1").milestoneAmount(new BigDecimal("50000")).build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError()).isEmpty();
        assertThat(milestone.getMilestoneAmount()).isEqualByComparingTo("50000");
        verify(cascadeDeleteService).markContainedEventsAsErrorOrBlock(Set.of("m1"));
    }

    @Test
    void update_blocks_whenShrinkingMilestoneWouldRequireFlaggingAnEventReachingOutsideTheProject() {
        ProjectEntity root = root(new BigDecimal("200000"));
        MilestoneEntity milestone = MilestoneEntity.builder().id("m1").proId("PRJ-1000-M1")
                .milestoneTitle("Milestone 1").milestoneAmount(new BigDecimal("150000")).currency("USD").project(root).build();
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(projectRepository.findByParentProjectId("root")).thenReturn(List.of());
        when(milestoneRepository.findByProjectIdAndProId("root", "PRJ-1000-M1")).thenReturn(Optional.of(milestone));
        when(milestoneService.findByProjectId("root")).thenReturn(List.of(milestone));
        when(projectRepository.findById("root")).thenReturn(Optional.of(root));
        when(milestoneService.needsErrorFlagging(eq("m1"), any())).thenReturn(true);
        ProblemDetail crossProjectConflict = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "reaches outside");
        crossProjectConflict.setTitle(ErrorTitleConstants.EVENT_ALLOCATED_TO_OTHER_PROJECTS);
        when(cascadeDeleteService.markContainedEventsAsErrorOrBlock(Set.of("m1"))).thenReturn(Optional.of(crossProjectConflict));

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setMilestones(List.of(MilestoneCreateRequest.builder()
                .proId("PRJ-1000-M1").milestoneTitle("Milestone 1").milestoneAmount(new BigDecimal("50000")).build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.EVENT_ALLOCATED_TO_OTHER_PROJECTS);
    }

    @Test
    void update_createsNewSubProject_whenNoExistingNodeMatchesItsProId() {
        ProjectEntity root = root(new BigDecimal("200000"));
        ProjectEntity newSub = subProject(root, "sub-new", "PRJ-1000-3", new BigDecimal("50000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(projectRepository.findByParentProjectId("root")).thenReturn(List.of());
        when(projectRepository.findByParentProjectIdAndProId("root", "PRJ-1000-3")).thenReturn(Optional.empty());
        when(projectStructureService.createSubProject(root, "New Sub", "PRJ-1000-3", null, new BigDecimal("50000"), null))
                .thenReturn(Either.right(newSub));
        when(milestoneService.findByProjectId("root")).thenReturn(List.of());
        when(milestoneService.findByProjectId("sub-new")).thenReturn(List.of());
        when(projectRepository.findByParentProjectId("sub-new")).thenReturn(List.of());
        when(projectRepository.findById("root")).thenReturn(Optional.of(root));
        when(projectRepository.findById("sub-new")).thenReturn(Optional.of(newSub));

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setSubProjects(List.of(ProjectTreeNodeRequest.builder().externalProjectId("x")
                .proId("PRJ-1000-3").projectTitle("New Sub").totalAmount(new BigDecimal("50000")).build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError()).isEmpty();
    }

    @Test
    void update_returns400_whenProIdBlank() {
        ProjectView result = service.updateWithMilestones(
                ProjectWithMilestonesCreateRequest.builder().organisationId("org1").externalProjectId("x").proId("").build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_FIELDS_REQUIRED);
    }

    @Test
    void update_returns401_whenOrgAccessDenied() {
        ProjectEntity root = root(new BigDecimal("200000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        ProjectView result = service.updateWithMilestones(request(null));

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.UNAUTHORIZED);
    }

    @Test
    void update_returns400_whenBothMilestonesAndSubProjectsPresentAtRoot() {
        ProjectEntity root = root(new BigDecimal("200000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setMilestones(List.of(MilestoneCreateRequest.builder().milestoneTitle("M1").build()));
        request.setSubProjects(List.of(ProjectTreeNodeRequest.builder().externalProjectId("x").projectTitle("Sub").build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SUBPROJECT_NOT_ALLOWED_WITH_MILESTONES);
    }

    @Test
    void update_returns409_whenRootTitleConflictsWithAnotherRootProject() {
        ProjectEntity root = root(new BigDecimal("200000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(projectRepository.existsByOrganisationIdAndProjectTitleAndParentProjectIsNullAndIdNot("org1", "New Title", "root")).thenReturn(true);

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setProjectTitle("New Title");

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS);
    }

    @Test
    void update_returns400_whenRootTotalAmountInvalid() {
        ProjectEntity root = root(new BigDecimal("200000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));

        ProjectView result = service.updateWithMilestones(request(new BigDecimal("-1")));

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_AMOUNT_INVALID);
    }

    @Test
    void update_returns400_whenRootCurrencyInvalid() {
        ProjectEntity root = root(new BigDecimal("200000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(milestoneService.isCurrencyRegisteredAndActive("org1", "XXX")).thenReturn(false);

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setCurrency("XXX");

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.CURRENCY_INVALID);
    }

    @Test
    void update_appliesRootTitleChange_whenNoConflict() {
        ProjectEntity root = root(new BigDecimal("200000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(projectRepository.findByParentProjectId("root")).thenReturn(List.of());
        when(milestoneService.findByProjectId("root")).thenReturn(List.of());
        when(projectRepository.findById("root")).thenReturn(Optional.of(root));

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setProjectTitle("Brand New Title");

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError()).isEmpty();
        assertThat(root.getProjectTitle()).isEqualTo("Brand New Title");
    }

    @Test
    void update_rollsBackWholeTree_whenARootLevelMilestoneFailsValidation() {
        ProjectEntity root = root(new BigDecimal("200000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        ProblemDetail milestoneProblem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "bad milestone");
        milestoneProblem.setTitle(ErrorTitleConstants.MILESTONE_FIELDS_REQUIRED);
        when(milestoneService.create(eq("root"), any(), isNull())).thenReturn(Either.left(milestoneProblem));

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setMilestones(List.of(MilestoneCreateRequest.builder().milestoneTitle("New Milestone").build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_FIELDS_REQUIRED);
        verify(projectRepository, never()).findById(any());
    }

    @Test
    void update_createsNewMilestoneDirectlyUnderRoot_whenNoExistingMilestoneMatches() {
        ProjectEntity root = root(new BigDecimal("200000"));
        MilestoneEntity created = MilestoneEntity.builder().id("m-new").proId("PRJ-1000-N1")
                .milestoneTitle("New Milestone").milestoneAmount(new BigDecimal("1000")).project(root).build();
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(milestoneService.create(eq("root"), any(), isNull())).thenReturn(Either.right(created));
        when(projectRepository.findByParentProjectId("root")).thenReturn(List.of());
        when(milestoneService.findByProjectId("root")).thenReturn(List.of(created));
        when(projectRepository.findById("root")).thenReturn(Optional.of(root));

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setMilestones(List.of(MilestoneCreateRequest.builder()
                .milestoneTitle("New Milestone").milestoneAmount(new BigDecimal("1000")).build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError()).isEmpty();
    }

    @Test
    void update_returns400_whenExistingMilestoneAmountInvalid() {
        ProjectEntity root = root(new BigDecimal("200000"));
        MilestoneEntity milestone = MilestoneEntity.builder().id("m1").proId("PRJ-1000-M1")
                .milestoneTitle("Milestone 1").milestoneAmount(new BigDecimal("50000")).currency("USD").project(root).build();
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(milestoneRepository.findByProjectIdAndProId("root", "PRJ-1000-M1")).thenReturn(Optional.of(milestone));

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setMilestones(List.of(MilestoneCreateRequest.builder().proId("PRJ-1000-M1")
                .milestoneTitle("Milestone 1").milestoneAmount(new BigDecimal("-5")).build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_AMOUNT_INVALID);
    }

    @Test
    void update_returns409_whenTwoSiblingSubProjectsShareTheSameTitle() {
        ProjectEntity root = root(new BigDecimal("200000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setSubProjects(List.of(
                ProjectTreeNodeRequest.builder().externalProjectId("x").projectTitle("Sub Dup").build(),
                ProjectTreeNodeRequest.builder().externalProjectId("x").projectTitle("Sub Dup").build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS);
    }

    @Test
    void update_returns400_whenASubProjectNodeHasBothMilestonesAndSubProjects() {
        ProjectEntity root = root(new BigDecimal("200000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setSubProjects(List.of(ProjectTreeNodeRequest.builder().externalProjectId("x").projectTitle("Bad Node")
                .milestones(List.of(MilestoneCreateRequest.builder().milestoneTitle("M").build()))
                .subProjects(List.of(ProjectTreeNodeRequest.builder().externalProjectId("x").projectTitle("Nested").build()))
                .build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SUBPROJECT_NOT_ALLOWED_WITH_MILESTONES);
    }

    @Test
    void update_returns409_whenCreatingNewSubProjectFails() {
        ProjectEntity root = root(new BigDecimal("200000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(projectRepository.findByParentProjectIdAndProjectTitle("root", "New Sub")).thenReturn(Optional.empty());
        ProblemDetail conflict = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "dup");
        conflict.setTitle(ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS);
        when(projectStructureService.createSubProject(eq(root), eq("New Sub"), isNull(), isNull(), eq(new BigDecimal("1000")), isNull()))
                .thenReturn(Either.left(conflict));

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setSubProjects(List.of(ProjectTreeNodeRequest.builder().externalProjectId("x").projectTitle("New Sub")
                .totalAmount(new BigDecimal("1000")).build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS);
    }

    @Test
    void update_matchesExistingSubProject_byTitleFallback_whenProIdBlank() {
        ProjectEntity root = root(new BigDecimal("200000"));
        ProjectEntity sub = subProject(root, "sub1", "PRJ-1000-1", new BigDecimal("50000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(projectRepository.findByParentProjectIdAndProjectTitle("root", "PRJ-1000-1")).thenReturn(Optional.of(sub));
        when(milestoneService.findByProjectId("root")).thenReturn(List.of());
        when(milestoneService.findByProjectId("sub1")).thenReturn(List.of());
        when(projectRepository.findByParentProjectId("root")).thenReturn(List.of(sub));
        when(projectRepository.findByParentProjectId("sub1")).thenReturn(List.of());
        when(projectRepository.findById("root")).thenReturn(Optional.of(root));
        when(projectRepository.findById("sub1")).thenReturn(Optional.of(sub));

        ProjectWithMilestonesCreateRequest request = request(new BigDecimal("200000"));
        request.setSubProjects(List.of(ProjectTreeNodeRequest.builder().externalProjectId("x")
                .projectTitle("PRJ-1000-1").totalAmount(new BigDecimal("60000")).build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError()).isEmpty();
        assertThat(sub.getTotalAmount()).isEqualByComparingTo("60000");
        verify(projectRepository, never()).findByParentProjectIdAndProId(any(), any());
    }

    @Test
    void update_matchesExistingMilestone_byTitleFallback_whenProIdBlank() {
        ProjectEntity root = root(new BigDecimal("200000"));
        MilestoneEntity milestone = MilestoneEntity.builder().id("m1").proId("PRJ-1000-M1")
                .milestoneTitle("Milestone 1").milestoneAmount(new BigDecimal("50000")).currency("USD").project(root).build();
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(milestoneRepository.findByProjectIdAndMilestoneTitle("root", "Milestone 1")).thenReturn(Optional.of(milestone));
        when(projectRepository.findByParentProjectId("root")).thenReturn(List.of());
        when(milestoneService.findByProjectId("root")).thenReturn(List.of(milestone));
        when(projectRepository.findById("root")).thenReturn(Optional.of(root));

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setMilestones(List.of(MilestoneCreateRequest.builder().milestoneTitle("Milestone 1")
                .milestoneAmount(new BigDecimal("60000")).build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError()).isEmpty();
        assertThat(milestone.getMilestoneAmount()).isEqualByComparingTo("60000");
        verify(milestoneRepository, never()).findByProjectIdAndProId(any(), any());
    }

    @Test
    void update_returns400_whenExistingMilestoneCurrencyInvalid() {
        ProjectEntity root = root(new BigDecimal("200000"));
        MilestoneEntity milestone = MilestoneEntity.builder().id("m1").proId("PRJ-1000-M1")
                .milestoneTitle("Milestone 1").milestoneAmount(new BigDecimal("50000")).currency("USD").project(root).build();
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(milestoneRepository.findByProjectIdAndProId("root", "PRJ-1000-M1")).thenReturn(Optional.of(milestone));
        when(milestoneService.isCurrencyRegisteredAndActive("org1", "XXX")).thenReturn(false);

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setMilestones(List.of(MilestoneCreateRequest.builder().proId("PRJ-1000-M1")
                .milestoneTitle("Milestone 1").currency("XXX").build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.CURRENCY_INVALID);
    }

    @Test
    void update_propagatesMilestoneTitleConflict_whenRenamingToAnExistingSiblingTitle() {
        ProjectEntity root = root(new BigDecimal("200000"));
        MilestoneEntity milestone = MilestoneEntity.builder().id("m1").proId("PRJ-1000-M1")
                .milestoneTitle("Milestone 1").milestoneAmount(new BigDecimal("50000")).currency("USD").project(root).build();
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(milestoneRepository.findByProjectIdAndProId("root", "PRJ-1000-M1")).thenReturn(Optional.of(milestone));
        ProblemDetail conflict = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "dup title");
        conflict.setTitle(ErrorTitleConstants.MILESTONE_TITLE_ALREADY_EXISTS);
        when(milestoneService.checkTitleConflict(eq(root), eq("m1"), any(), eq(true))).thenReturn(Optional.of(conflict));

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setMilestones(List.of(MilestoneCreateRequest.builder().proId("PRJ-1000-M1")
                .milestoneTitle("Milestone 2").build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_TITLE_ALREADY_EXISTS);
    }

    @Test
    void update_returns409_whenSubProjectTitleConflictsWithSibling() {
        ProjectEntity root = root(new BigDecimal("200000"));
        ProjectEntity sub = subProject(root, "sub1", "PRJ-1000-1", new BigDecimal("50000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(projectRepository.findByParentProjectIdAndProId("root", "PRJ-1000-1")).thenReturn(Optional.of(sub));
        when(projectRepository.existsByParentProjectIdAndProjectTitleAndIdNot("root", "Renamed Sub", "sub1")).thenReturn(true);

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setSubProjects(List.of(ProjectTreeNodeRequest.builder().externalProjectId("x").proId("PRJ-1000-1")
                .projectTitle("Renamed Sub").build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS);
    }

    @Test
    void update_returns400_whenSubProjectTotalAmountInvalid() {
        ProjectEntity root = root(new BigDecimal("200000"));
        ProjectEntity sub = subProject(root, "sub1", "PRJ-1000-1", new BigDecimal("50000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(projectRepository.findByParentProjectIdAndProId("root", "PRJ-1000-1")).thenReturn(Optional.of(sub));

        ProjectWithMilestonesCreateRequest request = request(null);
        request.setSubProjects(List.of(ProjectTreeNodeRequest.builder().externalProjectId("x").proId("PRJ-1000-1")
                .totalAmount(new BigDecimal("-5")).build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_AMOUNT_INVALID);
    }

    @Test
    void update_appliesSubProjectTitleChange_whenNoConflict() {
        ProjectEntity root = root(new BigDecimal("200000"));
        ProjectEntity sub = subProject(root, "sub1", "PRJ-1000-1", new BigDecimal("50000"));
        when(projectRepository.findByOrganisationIdAndProIdAndParentProjectIsNull("org1", "PRJ-1000")).thenReturn(Optional.of(root));
        when(projectRepository.findByParentProjectIdAndProId("root", "PRJ-1000-1")).thenReturn(Optional.of(sub));
        when(milestoneService.findByProjectId("root")).thenReturn(List.of());
        when(milestoneService.findByProjectId("sub1")).thenReturn(List.of());
        when(projectRepository.findByParentProjectId("root")).thenReturn(List.of(sub));
        when(projectRepository.findByParentProjectId("sub1")).thenReturn(List.of());
        when(projectRepository.findById("root")).thenReturn(Optional.of(root));
        when(projectRepository.findById("sub1")).thenReturn(Optional.of(sub));

        ProjectWithMilestonesCreateRequest request = request(new BigDecimal("200000"));
        request.setSubProjects(List.of(ProjectTreeNodeRequest.builder().externalProjectId("x").proId("PRJ-1000-1")
                .projectTitle("Renamed Sub").build()));

        ProjectView result = service.updateWithMilestones(request);

        assertThat(result.getError()).isEmpty();
        assertThat(sub.getProjectTitle()).isEqualTo("Renamed Sub");
    }

    private ProjectWithMilestonesCreateRequest request(BigDecimal totalAmount) {
        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("x").projectTitle("Updated Project Name")
                .proId("PRJ-1000").totalAmount(totalAmount).build();
        request.setMilestones(List.of());
        request.setSubProjects(List.of());
        return request;
    }

}
