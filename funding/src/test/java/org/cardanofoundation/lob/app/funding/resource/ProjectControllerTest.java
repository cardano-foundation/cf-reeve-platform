package org.cardanofoundation.lob.app.funding.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.service.ProjectService;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;

@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private ProjectController projectController;

    private static final Pageable PAGEABLE = PageRequest.of(0, 10);

    private ProjectView projectView() {
        return ProjectView.builder().projectId("p1").organisationId("org1").externalProjectId("PROJ-AB")
                .projectTitle("Project AB").totalAmount(new BigDecimal("200000.00")).currency("USD").build();
    }

    private ProblemDetail problem(HttpStatus status, String title) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, "detail");
        p.setTitle(title);
        return p;
    }

    // --- listProjects ---

    @Test
    void listProjects_returns200_withContent() {
        PagedResponse<ProjectView> paged = PagedResponse.<ProjectView>builder().content(List.of(projectView())).total(1).build();
        when(projectService.listProjects("org1", PAGEABLE)).thenReturn(paged);

        ResponseEntity<?> response = projectController.listProjects("org1", PAGEABLE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((PagedResponse<?>) response.getBody()).getContent()).hasSize(1);
    }

    @Test
    void listProjects_propagatesErrorStatus() {
        when(projectService.listProjects("org1", PAGEABLE))
                .thenReturn(PagedResponse.error(problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED")));

        ResponseEntity<?> response = projectController.listProjects("org1", PAGEABLE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(((PagedResponse<?>) response.getBody()).getError()).isPresent();
    }

    // --- getProject ---

    @Test
    void getProject_returns200_withView() {
        ProjectView view = projectView();
        when(projectService.getProject("p1")).thenReturn(view);

        ResponseEntity<?> response = projectController.getProject("p1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void getProject_returns404_withProblem() {
        when(projectService.getProject("p1"))
                .thenReturn(ProjectView.error(problem(HttpStatus.NOT_FOUND, ErrorTitleConstants.PROJECT_NOT_FOUND)));

        ResponseEntity<?> response = projectController.getProject("p1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProjectView) response.getBody()).getError().orElseThrow().getTitle())
                .isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    // --- listSubProjects ---

    @Test
    void listSubProjects_returns200() {
        PagedResponse<ProjectView> paged = PagedResponse.<ProjectView>builder().content(List.of(projectView())).total(1).build();
        when(projectService.listSubProjects("p1", PAGEABLE)).thenReturn(paged);

        ResponseEntity<?> response = projectController.listSubProjects("p1", PAGEABLE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((PagedResponse<?>) response.getBody()).getContent()).hasSize(1);
    }

    // --- createProjectWithMilestones ---

    @Test
    void create_returns201_withView() {
        ProjectView view = projectView();
        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder().build();
        when(projectService.createWithMilestones(request)).thenReturn(view);

        ResponseEntity<?> response = projectController.createProjectWithMilestones(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void create_returns409_withProblem() {
        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder().build();
        when(projectService.createWithMilestones(request))
                .thenReturn(ProjectView.error(problem(HttpStatus.CONFLICT, ErrorTitleConstants.PROJECT_ALREADY_EXISTS)));

        ResponseEntity<?> response = projectController.createProjectWithMilestones(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((ProjectView) response.getBody()).getError().orElseThrow().getTitle())
                .isEqualTo(ErrorTitleConstants.PROJECT_ALREADY_EXISTS);
    }

    // --- updateProject ---

    @Test
    void update_returns200_withView() {
        ProjectView view = projectView();
        ProjectUpdateRequest request = ProjectUpdateRequest.builder().projectTitle("New").build();
        when(projectService.updateProject("p1", request)).thenReturn(view);

        ResponseEntity<?> response = projectController.updateProject("p1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- deleteProject ---

    @Test
    void delete_returns204_whenNoError() {
        when(projectService.deleteProject("p1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = projectController.deleteProject("p1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void delete_returns404_withProblem() {
        ProblemDetail p = problem(HttpStatus.NOT_FOUND, ErrorTitleConstants.PROJECT_NOT_FOUND);
        when(projectService.deleteProject("p1")).thenReturn(Optional.of(p));

        ResponseEntity<?> response = projectController.deleteProject("p1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

}
