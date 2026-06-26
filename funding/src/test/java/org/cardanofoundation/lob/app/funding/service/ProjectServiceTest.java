package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
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

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private FundingProjectRepository projectRepository;
    @Mock
    private MilestoneService milestoneService;
    @Mock
    private EventMilestoneAllocationRepository allocationRepository;

    @InjectMocks
    private ProjectService projectService;

    @Test
    void findById_delegatesToRepository() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        assertThat(projectService.findById("p1")).contains(project);
    }

    @Test
    void findById_returnsEmpty_whenNotFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.empty());

        assertThat(projectService.findById("p1")).isEmpty();
    }

    @Test
    void findByOrganisationId_returnsList() {
        List<ProjectEntity> projects = List.of(projectEntity());
        when(projectRepository.findByOrganisationId("org1")).thenReturn(projects);

        assertThat(projectService.findByOrganisationId("org1")).isEqualTo(projects);
    }

    @Test
    void findByOrganisationId_withPageable_returnsPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<ProjectEntity> page = new PageImpl<>(List.of(projectEntity()));
        when(projectRepository.findByOrganisationId("org1", pageable)).thenReturn(page);

        assertThat(projectService.findByOrganisationId("org1", pageable)).isEqualTo(page);
    }

    @Test
    void existsByOrganisationIdAndProjectId_delegatesToRepository() {
        when(projectRepository.existsByOrganisationIdAndProjectId("org1", "PROJ-AB")).thenReturn(true);

        assertThat(projectService.existsByOrganisationIdAndProjectId("org1", "PROJ-AB")).isTrue();
    }

    @Test
    void existsByOrganisationIdAndProjectId_returnsFalse_whenNotExists() {
        when(projectRepository.existsByOrganisationIdAndProjectId("org1", "NO-PROJECT")).thenReturn(false);

        assertThat(projectService.existsByOrganisationIdAndProjectId("org1", "NO-PROJECT")).isFalse();
    }

    @Test
    void createWithMilestones_withNoMilestones_createsProjectOnly() {
        ProjectEntity saved = projectEntity();
        when(projectRepository.saveAndFlush(any())).thenReturn(saved);
        when(projectRepository.findById(saved.getId())).thenReturn(Optional.of(saved));

        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1")
                .fundingId("GRANT-2025-001")
                .projectId("PROJ-AB")
                .projectTitle("Project AB")
                .totalAmount(new BigDecimal("200000.00"))
                .currency("USD")
                .milestones(List.of())
                .build();

        ProjectEntity result = projectService.createWithMilestones(request);

        assertThat(result).isEqualTo(saved);
        verify(milestoneService, never()).create(any(), any());
    }

    @Test
    void createWithMilestones_createsProjectAndMilestones() {
        ProjectEntity saved = projectEntity();
        when(projectRepository.saveAndFlush(any())).thenReturn(saved);
        when(projectRepository.findById(saved.getId())).thenReturn(Optional.of(saved));
        when(milestoneService.create(eq(saved.getId()), any())).thenReturn(Either.left(ProblemDetail.forStatus(HttpStatus.NOT_FOUND)));

        MilestoneCreateRequest milestoneReq = MilestoneCreateRequest.builder()
                .milestoneTitle("MS-1")
                .milestoneAmount(new BigDecimal("50000.00"))
                .currency("USD")
                .milestoneDate(java.time.LocalDate.of(2025, 6, 30))
                .build();

        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1")
                .fundingId("GRANT-2025-001")
                .projectId("PROJ-AB")
                .projectTitle("Project AB")
                .totalAmount(new BigDecimal("200000.00"))
                .currency("USD")
                .milestones(List.of(milestoneReq))
                .build();

        ProjectEntity result = projectService.createWithMilestones(request);

        assertThat(result).isEqualTo(saved);
        verify(milestoneService).create(saved.getId(), milestoneReq);
    }

    @Test
    void update_returnsEmpty_whenProjectNotFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.empty());

        assertThat(projectService.update("p1", ProjectUpdateRequest.builder().build()).isLeft()).isTrue();
    }

    @Test
    void update_updatesProvidedFields() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(allocationRepository.existsByMilestone_Project_IdAndEvent_Status("p1", EventStatus.PUBLISHED)).thenReturn(false);
        when(projectRepository.saveAndFlush(project)).thenReturn(project);

        ProjectUpdateRequest request = ProjectUpdateRequest.builder()
                .projectTitle("Updated Title")
                .totalAmount(new BigDecimal("250000.00"))
                .currency("EUR")
                .build();

        Either<ProblemDetail, ProjectEntity> result = projectService.update("p1", request);

        assertThat(result.isRight()).isTrue();
        assertThat(project.getProjectTitle()).isEqualTo("Updated Title");
        assertThat(project.getTotalAmount()).isEqualByComparingTo("250000.00");
        assertThat(project.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void update_returnsConflict_whenLinkedToPublishedEvent() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(allocationRepository.existsByMilestone_Project_IdAndEvent_Status("p1", EventStatus.PUBLISHED)).thenReturn(true);

        Either<ProblemDetail, ProjectEntity> result = projectService.update("p1", ProjectUpdateRequest.builder().projectTitle("New").build());

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("SPENDING_EVENT_ALREADY_PUBLISHED");
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_skipsNullFields() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(allocationRepository.existsByMilestone_Project_IdAndEvent_Status("p1", EventStatus.PUBLISHED)).thenReturn(false);
        when(projectRepository.saveAndFlush(project)).thenReturn(project);

        projectService.update("p1", ProjectUpdateRequest.builder().build());

        assertThat(project.getProjectTitle()).isEqualTo("Project AB");
        assertThat(project.getCurrency()).isEqualTo("USD");
    }

    @Test
    void delete_returnsFalse_whenNotFound() {
        when(projectRepository.existsById("p1")).thenReturn(false);

        assertThat(projectService.delete("p1").isLeft()).isTrue();
        verify(projectRepository, never()).deleteById(any());
    }

    @Test
    void delete_deletesAndReturnsTrue_whenFound() {
        when(projectRepository.existsById("p1")).thenReturn(true);
        when(allocationRepository.existsByMilestone_Project_IdAndEvent_Status("p1", EventStatus.PUBLISHED)).thenReturn(false);

        assertThat(projectService.delete("p1").isRight()).isTrue();
        verify(projectRepository).deleteById("p1");
    }

    @Test
    void delete_returnsConflict_whenLinkedToPublishedEvent() {
        when(projectRepository.existsById("p1")).thenReturn(true);
        when(allocationRepository.existsByMilestone_Project_IdAndEvent_Status("p1", EventStatus.PUBLISHED)).thenReturn(true);

        Either<ProblemDetail, Void> result = projectService.delete("p1");

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("SPENDING_EVENT_ALREADY_PUBLISHED");
        verify(projectRepository, never()).deleteById(any());
    }

    @Test
    void toView_includesSubProjects() {
        ProjectEntity parent = projectEntity();
        ProjectEntity subProject = ProjectEntity.builder()
                .id("sub-p1").organisationId("org1").projectId("SUB-AB")
                .projectTitle("Sub Project AB").parentProject(parent).build();

        when(milestoneService.findByProjectId(parent.getId())).thenReturn(List.of());
        when(projectRepository.findByParentProject_Id(parent.getId())).thenReturn(List.of(subProject));
        when(milestoneService.findByProjectId(subProject.getId())).thenReturn(List.of());
        when(projectRepository.findByParentProject_Id(subProject.getId())).thenReturn(List.of());

        ProjectView view = projectService.toView(parent);

        assertThat(view.getSubProjects()).hasSize(1);
<<<<<<< Updated upstream
        assertThat(view.getSubProjects().get(0).getProjectId()).isEqualTo("SUB-AB");
        assertThat(view.getParentProjectUid()).isNull();
=======
        assertThat(view.getSubProjects().get(0).getExternalProjectId()).isEqualTo("SUB-AB");
        assertThat(view.getParentProjectId()).isNull();
>>>>>>> Stashed changes
    }

    @Test
    void toView_setsParentProjectUid_forSubProject() {
        ProjectEntity parent = projectEntity();
        ProjectEntity subProject = ProjectEntity.builder()
                .id("sub-p1").organisationId("org1").projectId("SUB-AB")
                .projectTitle("Sub Project AB").parentProject(parent).build();

        when(milestoneService.findByProjectId(subProject.getId())).thenReturn(List.of());
        when(projectRepository.findByParentProject_Id(subProject.getId())).thenReturn(List.of());

        ProjectView view = projectService.toView(subProject);

        assertThat(view.getParentProjectId()).isEqualTo(parent.getId());
    }

    @Test
    void toView_mapsProjectWithMilestonesAndEvents() {
        ProjectEntity project = projectEntity();
        MilestoneEntity milestone = MilestoneEntity.builder()
                .id("m1").milestoneTitle("MS-1").milestoneAmount(new BigDecimal("50000")).currency("USD")
                .milestoneDate(java.time.LocalDate.of(2025, 6, 30)).project(project).build();

        MilestoneView milestoneView = MilestoneView.builder()
                .milestoneUid("m1").projectUid(project.getId()).milestoneTitle("MS-1")
                .milestoneAmount(new BigDecimal("50000")).currency("USD")
                .milestoneDate(java.time.LocalDate.of(2025, 6, 30)).build();

        when(milestoneService.findByProjectId(project.getId())).thenReturn(List.of(milestone));
        when(milestoneService.toView(milestone)).thenReturn(milestoneView);

        ProjectView view = projectService.toView(project);

        assertThat(view.getProjectUid()).isEqualTo(project.getId());
        assertThat(view.getOrganisationId()).isEqualTo("org1");
        assertThat(view.getFundingId()).isEqualTo("GRANT-2025-001");
        assertThat(view.getProjectId()).isEqualTo("PROJ-AB");
        assertThat(view.getProjectTitle()).isEqualTo("Project AB");
        assertThat(view.getCurrency()).isEqualTo("USD");
        assertThat(view.getMilestones()).containsExactly(milestoneView);
    }

    // --- helpers ---

    private ProjectEntity projectEntity() {
        return ProjectEntity.builder()
                .id(ProjectEntity.id("org1", "PROJ-AB"))
                .organisationId("org1")
                .fundingId("GRANT-2025-001")
                .projectId("PROJ-AB")
                .projectTitle("Project AB")
                .totalAmount(new BigDecimal("200000.00"))
                .currency("USD")
                .build();
    }

}
