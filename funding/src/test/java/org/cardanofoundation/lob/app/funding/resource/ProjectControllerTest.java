package org.cardanofoundation.lob.app.funding.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.http.ResponseEntity;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.service.ProjectService;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private OrganisationPublicApiIF organisationPublicApi;

    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @InjectMocks
    private ProjectController projectController;

    @BeforeEach
    void allowOrgAccessByDefault() {
        lenient().when(keycloakSecurityHelper.canUserAccessOrg(any())).thenReturn(true);
    }

    // --- listProjects ---

    @Test
    void listProjects_returns200_withList() {
        Pageable pageable = PageRequest.of(0, 10);
        ProjectEntity project = projectEntity();
        ProjectView view = projectView();
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(mock(Organisation.class)));
        when(projectService.findByOrganisationId("org1", pageable)).thenReturn(new PageImpl<>(List.of(project)));
        when(projectService.toView(project)).thenReturn(view);

        ResponseEntity<?> response = projectController.listProjects("org1", pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((PagedResponse<?>) response.getBody()).getContent()).isEqualTo(List.of(view));
    }

    @Test
    void listProjects_returns200_withEmptyList() {
        Pageable pageable = PageRequest.of(0, 10);
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(mock(Organisation.class)));
        when(projectService.findByOrganisationId("org1", pageable)).thenReturn(Page.empty());

        ResponseEntity<?> response = projectController.listProjects("org1", pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((PagedResponse<?>) response.getBody()).getContent()).isEqualTo(List.of());
    }

    @Test
    void listProjects_returns400_whenOrgNotFound() {
        when(organisationPublicApi.findByOrganisationId("unknown")).thenReturn(Optional.empty());

        ResponseEntity<?> response = projectController.listProjects("unknown", PageRequest.of(0, 10));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listProjects_returns401_whenNoOrgAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        ResponseEntity<?> response = projectController.listProjects("org1", PageRequest.of(0, 10));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- listSubProjects ---

    @Test
    void listSubProjects_returns200_withContent() {
        Pageable pageable = PageRequest.of(0, 10);
        ProjectEntity parent = projectEntity();
        ProjectView subView = projectView();
        when(projectService.findById("p1")).thenReturn(Optional.of(parent));
        when(projectService.findSubProjects("p1", pageable)).thenReturn(new PageImpl<>(List.of(parent)));
        when(projectService.toView(parent)).thenReturn(subView);

        ResponseEntity<?> response = projectController.listSubProjects("p1", pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((PagedResponse<?>) response.getBody()).getContent()).isEqualTo(List.of(subView));
    }

    @Test
    void listSubProjects_returns404_whenParentNotFound() {
        when(projectService.findById("nope")).thenReturn(Optional.empty());

        ResponseEntity<?> response = projectController.listSubProjects("nope", PageRequest.of(0, 10));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getProject_returns401_whenNoOrgAccess() {
        ProjectEntity project = projectEntity();
        when(projectService.findById("p1")).thenReturn(Optional.of(project));
        when(keycloakSecurityHelper.canUserAccessOrg(project.getOrganisationId())).thenReturn(false);

        ResponseEntity<?> response = projectController.getProject("p1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- getProject ---

    @Test
    void getProject_returns404_whenNotFound() {
        when(projectService.findById("p1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = projectController.getProject("p1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    @Test
    void getProject_returns200_withView() {
        ProjectEntity project = projectEntity();
        ProjectView view = projectView();
        when(projectService.findById("p1")).thenReturn(Optional.of(project));
        when(projectService.toView(project)).thenReturn(view);

        ResponseEntity<?> response = projectController.getProject("p1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- createProjectWithMilestones ---

    @Test
    void createProjectWithMilestones_returns409_whenAlreadyExists() {
        ProjectWithMilestonesCreateRequest request = createWithMilestonesRequest();
        when(projectService.existsByOrganisationIdAndExternalProjectId("org1", "PROJ-AB")).thenReturn(true);

        ResponseEntity<?> response = projectController.createProjectWithMilestones(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_ALREADY_EXISTS);
        verify(projectService, never()).createWithMilestones(any());
    }

    @Test
    void createProjectWithMilestones_returns201_withView() {
        ProjectWithMilestonesCreateRequest request = createWithMilestonesRequest();
        ProjectEntity project = projectEntity();
        ProjectView view = projectView();
        when(projectService.existsByOrganisationIdAndExternalProjectId("org1", "PROJ-AB")).thenReturn(false);
        when(projectService.createWithMilestones(request)).thenReturn(Either.right(project));
        when(projectService.toView(project)).thenReturn(view);

        ResponseEntity<?> response = projectController.createProjectWithMilestones(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- updateProject ---

    @Test
    void updateProject_returns404_whenNotFound() {
        ProjectUpdateRequest request = ProjectUpdateRequest.builder().projectTitle("New").build();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Project not found");
        problem.setTitle(ErrorTitleConstants.PROJECT_NOT_FOUND);
        when(projectService.update("p1", request)).thenReturn(Either.left(problem));

        ResponseEntity<?> response = projectController.updateProject("p1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    @Test
    void updateProject_returns200_withView() {
        ProjectUpdateRequest request = ProjectUpdateRequest.builder().projectTitle("New").build();
        ProjectEntity project = projectEntity();
        ProjectView view = projectView();
        when(projectService.update("p1", request)).thenReturn(Either.right(project));
        when(projectService.toView(project)).thenReturn(view);

        ResponseEntity<?> response = projectController.updateProject("p1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- deleteProject ---

    @Test
    void deleteProject_returns404_whenNotFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Project not found");
        problem.setTitle(ErrorTitleConstants.PROJECT_NOT_FOUND);
        when(projectService.delete("p1")).thenReturn(Either.left(problem));

        ResponseEntity<?> response = projectController.deleteProject("p1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    @Test
    void deleteProject_returns204_whenDeleted() {
        when(projectService.delete("p1")).thenReturn(Either.right(null));

        ResponseEntity<?> response = projectController.deleteProject("p1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // --- helpers ---

    private ProjectEntity projectEntity() {
        return ProjectEntity.builder()
                .id("p1").organisationId("org1").fundingId("GRANT-2025-001")
                .externalProjectId("PROJ-AB").projectTitle("Project AB")
                .totalAmount(new BigDecimal("200000.00")).currency("USD").build();
    }

    private ProjectView projectView() {
        return ProjectView.builder()
                .projectId("p1").organisationId("org1").fundingId("GRANT-2025-001")
                .externalProjectId("PROJ-AB").projectTitle("Project AB")
                .totalAmount(new BigDecimal("200000.00")).currency("USD")
                .milestones(List.of()).build();
    }

    private ProjectWithMilestonesCreateRequest createWithMilestonesRequest() {
        return ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").fundingId("GRANT-2025-001")
                .externalProjectId("PROJ-AB").projectTitle("Project AB")
                .totalAmount(new BigDecimal("200000.00")).currency("USD")
                .milestones(List.of()).build();
    }

}
