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

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private FundingProjectRepository projectRepository;
    @Mock
    private MilestoneService milestoneService;
    @Mock
    private SpendingEventService spendingEventService;

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
    void existsByOrganisationIdAndActivityId_delegatesToRepository() {
        when(projectRepository.existsByOrganisationIdAndActivityId("org1", "PROJ-AB")).thenReturn(true);

        assertThat(projectService.existsByOrganisationIdAndActivityId("org1", "PROJ-AB")).isTrue();
    }

    @Test
    void createWithMilestones_createsProjectAndMilestones() {
        ProjectEntity saved = projectEntity();
        when(projectRepository.saveAndFlush(any())).thenReturn(saved);
        when(projectRepository.findById(saved.getId())).thenReturn(Optional.of(saved));
        when(milestoneService.create(eq(saved.getId()), any())).thenReturn(Optional.empty());

        MilestoneCreateRequest milestoneReq = MilestoneCreateRequest.builder()
                .label("MS-1")
                .expectedCost(new BigDecimal("50000.00"))
                .currency("USD")
                .dueDate(java.time.LocalDate.of(2025, 6, 30))
                .build();

        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1")
                .fundingId("GRANT-2025-001")
                .activityId("PROJ-AB")
                .activityTitle("Project AB")
                .expectedTotalAmount(new BigDecimal("200000.00"))
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

        assertThat(projectService.update("p1", ProjectUpdateRequest.builder().build())).isEmpty();
    }

    @Test
    void update_updatesProvidedFields() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(projectRepository.saveAndFlush(project)).thenReturn(project);

        ProjectUpdateRequest request = ProjectUpdateRequest.builder()
                .activityTitle("Updated Title")
                .expectedTotalAmount(new BigDecimal("250000.00"))
                .currency("EUR")
                .build();

        Optional<ProjectEntity> result = projectService.update("p1", request);

        assertThat(result).isPresent();
        assertThat(project.getActivityTitle()).isEqualTo("Updated Title");
        assertThat(project.getExpectedTotalAmount()).isEqualByComparingTo("250000.00");
        assertThat(project.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void update_skipsNullFields() {
        ProjectEntity project = projectEntity();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(projectRepository.saveAndFlush(project)).thenReturn(project);

        projectService.update("p1", ProjectUpdateRequest.builder().build());

        assertThat(project.getActivityTitle()).isEqualTo("Project AB");
        assertThat(project.getCurrency()).isEqualTo("USD");
    }

    @Test
    void delete_returnsFalse_whenNotFound() {
        when(projectRepository.existsById("p1")).thenReturn(false);

        assertThat(projectService.delete("p1")).isFalse();
        verify(projectRepository, never()).deleteById(any());
    }

    @Test
    void delete_deletesAndReturnsTrue_whenFound() {
        when(projectRepository.existsById("p1")).thenReturn(true);

        assertThat(projectService.delete("p1")).isTrue();
        verify(projectRepository).deleteById("p1");
    }

    @Test
    void toView_mapsProjectWithMilestonesAndEvents() {
        ProjectEntity project = projectEntity();
        MilestoneEntity milestone = MilestoneEntity.builder()
                .id("m1").label("MS-1").expectedCost(new BigDecimal("50000")).currency("USD")
                .dueDate(java.time.LocalDate.of(2025, 6, 30)).project(project).build();

        MilestoneView milestoneView = MilestoneView.builder()
                .milestoneId("m1").projectId(project.getId()).label("MS-1").expectedCost(new BigDecimal("50000")).currency("USD")
                .dueDate(java.time.LocalDate.of(2025, 6, 30)).build();

        when(milestoneService.findByProjectId(project.getId())).thenReturn(List.of(milestone));
        when(milestoneService.toView(milestone)).thenReturn(milestoneView);
        when(spendingEventService.findByProjectId(project.getId())).thenReturn(List.of());

        ProjectView view = projectService.toView(project);

        assertThat(view.getProjectId()).isEqualTo(project.getId());
        assertThat(view.getOrganisationId()).isEqualTo("org1");
        assertThat(view.getFundingId()).isEqualTo("GRANT-2025-001");
        assertThat(view.getActivityId()).isEqualTo("PROJ-AB");
        assertThat(view.getActivityTitle()).isEqualTo("Project AB");
        assertThat(view.getCurrency()).isEqualTo("USD");
        assertThat(view.getMilestones()).containsExactly(milestoneView);
        assertThat(view.getEvents()).isEmpty();
    }

    // --- helpers ---

    private ProjectEntity projectEntity() {
        return ProjectEntity.builder()
                .id(ProjectEntity.id("org1", "PROJ-AB"))
                .organisationId("org1")
                .fundingId("GRANT-2025-001")
                .activityId("PROJ-AB")
                .activityTitle("Project AB")
                .expectedTotalAmount(new BigDecimal("200000.00"))
                .currency("USD")
                .build();
    }

}
