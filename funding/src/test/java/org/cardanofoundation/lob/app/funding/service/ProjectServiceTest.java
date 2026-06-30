package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
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
    private EventMilestoneAllocationRepository allocationRepository;
    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;
    @Mock
    private OrganisationPublicApiIF organisationPublicApi;

    @InjectMocks
    private ProjectService projectService;

    private static final Pageable PAGEABLE = PageRequest.of(0, 10);

    @BeforeEach
    void allowOrgAndEmptyChildren() {
        lenient().when(keycloakSecurityHelper.canUserAccessOrg(any())).thenReturn(true);
        lenient().when(milestoneService.findByProjectId(any())).thenReturn(List.of());
        lenient().when(projectRepository.findByParentProjectId(any(String.class))).thenReturn(List.of());
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
    void delete_conflict_whenLinkedToPublishedEvent() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(allocationRepository.existsByMilestoneProjectIdAndEventStatus("p1", EventStatus.PUBLISHED)).thenReturn(true);

        Optional<ProblemDetail> result = projectService.deleteProject("p1");

        assertThat(result.orElseThrow().getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void delete_success() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(allocationRepository.existsByMilestoneProjectIdAndEventStatus("p1", EventStatus.PUBLISHED)).thenReturn(false);

        Optional<ProblemDetail> result = projectService.deleteProject("p1");

        assertThat(result).isEmpty();
        verify(projectRepository).deleteById("p1");
    }

    private ProjectWithMilestonesCreateRequest createRequest() {
        return ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("PROJ-AB").projectTitle("Project AB")
                .fundingId("GRANT-2025-001").totalAmount(new BigDecimal("200000.00")).currency("USD")
                .milestones(List.of()).build();
    }

}
