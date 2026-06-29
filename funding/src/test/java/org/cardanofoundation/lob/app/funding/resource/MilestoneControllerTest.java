package org.cardanofoundation.lob.app.funding.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.service.MilestoneService;
import org.cardanofoundation.lob.app.funding.service.ProjectService;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class MilestoneControllerTest {

    @Mock
    private MilestoneService milestoneService;
    @Mock
    private ProjectService projectService;
    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @InjectMocks
    private MilestoneController milestoneController;

    @BeforeEach
    void allowOrgAccessByDefault() {
        lenient().when(keycloakSecurityHelper.canUserAccessOrg(any())).thenReturn(true);
    }

    // --- listMilestones ---

    @Test
    void listMilestones_returns404_whenProjectNotFound() {
        when(projectService.findById("p1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = milestoneController.listMilestones("p1", PageRequest.of(0, 10));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    @Test
    void listMilestones_returns200_withList() {
        Pageable pageable = PageRequest.of(0, 10);
        ProjectEntity project = projectEntity("p1");
        when(projectService.findById("p1")).thenReturn(Optional.of(project));
        MilestoneEntity milestone = milestoneEntity("m1");
        MilestoneView view = milestoneView("m1");
        when(milestoneService.findByProjectId("p1", pageable)).thenReturn(new PageImpl<>(List.of(milestone)));
        when(milestoneService.toView(milestone)).thenReturn(view);

        ResponseEntity<?> response = milestoneController.listMilestones("p1", pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((PagedResponse<?>) response.getBody()).getContent()).isEqualTo(List.of(view));
    }

    @Test
    void listMilestones_returns401_whenNoOrgAccess() {
        ProjectEntity project = projectEntity("p1");
        when(projectService.findById("p1")).thenReturn(Optional.of(project));
        when(keycloakSecurityHelper.canUserAccessOrg(project.getOrganisationId())).thenReturn(false);

        ResponseEntity<?> response = milestoneController.listMilestones("p1", PageRequest.of(0, 10));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- getMilestone ---

    @Test
    void getMilestone_returns404_whenNotFound() {
        when(projectService.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(milestoneService.findByIdAndProjectId("m1", "p1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = milestoneController.getMilestone("p1", "m1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_NOT_FOUND);
    }

    @Test
    void getMilestone_returns404_whenMilestoneNotInProject() {
        // IDOR guard: a milestone that exists but belongs to another project must not be reachable.
        when(projectService.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(milestoneService.findByIdAndProjectId("m-other", "p1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = milestoneController.getMilestone("p1", "m-other");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_NOT_FOUND);
    }

    @Test
    void getMilestone_returns401_whenNoOrgAccess() {
        ProjectEntity project = projectEntity("p1");
        when(projectService.findById("p1")).thenReturn(Optional.of(project));
        when(keycloakSecurityHelper.canUserAccessOrg(project.getOrganisationId())).thenReturn(false);

        ResponseEntity<?> response = milestoneController.getMilestone("p1", "m1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getMilestone_returns200_withView() {
        MilestoneEntity milestone = milestoneEntity("m1");
        MilestoneView view = milestoneView("m1");
        when(projectService.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(milestoneService.findByIdAndProjectId("m1", "p1")).thenReturn(Optional.of(milestone));
        when(milestoneService.toView(milestone)).thenReturn(view);

        ResponseEntity<?> response = milestoneController.getMilestone("p1", "m1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- createMilestone ---

    @Test
    void createMilestone_returns404_whenProjectNotFound() {
        MilestoneCreateRequest request = createRequest();
        when(projectService.findById("p1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = milestoneController.createMilestone("p1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
        verify(milestoneService, never()).create(any(), any());
    }

    @Test
    void createMilestone_returns201_withView() {
        MilestoneCreateRequest request = createRequest();
        MilestoneEntity milestone = milestoneEntity("m-new");
        MilestoneView view = milestoneView("m-new");
        when(projectService.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(milestoneService.create("p1", request)).thenReturn(Either.right(milestone));
        when(milestoneService.toView(milestone)).thenReturn(view);

        ResponseEntity<?> response = milestoneController.createMilestone("p1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- updateMilestone ---

    @Test
    void updateMilestone_returns404_whenNotFound() {
        MilestoneUpdateRequest request = MilestoneUpdateRequest.builder().milestoneTitle("New").build();
        when(projectService.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(milestoneService.findByIdAndProjectId("m1", "p1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = milestoneController.updateMilestone("p1", "m1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_NOT_FOUND);
        verify(milestoneService, never()).update(any(), any());
    }

    @Test
    void updateMilestone_returns200_withView() {
        MilestoneUpdateRequest request = MilestoneUpdateRequest.builder().milestoneTitle("New").build();
        MilestoneEntity milestone = milestoneEntity("m1");
        MilestoneView view = milestoneView("m1");
        when(projectService.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(milestoneService.findByIdAndProjectId("m1", "p1")).thenReturn(Optional.of(milestone));
        when(milestoneService.update("m1", request)).thenReturn(Either.right(milestone));
        when(milestoneService.toView(milestone)).thenReturn(view);

        ResponseEntity<?> response = milestoneController.updateMilestone("p1", "m1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- deleteMilestone ---

    @Test
    void deleteMilestone_returns404_whenNotFound() {
        when(projectService.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(milestoneService.findByIdAndProjectId("m1", "p1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = milestoneController.deleteMilestone("p1", "m1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_NOT_FOUND);
        verify(milestoneService, never()).delete(any());
    }

    @Test
    void deleteMilestone_returns204_whenDeleted() {
        when(projectService.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(milestoneService.findByIdAndProjectId("m1", "p1")).thenReturn(Optional.of(milestoneEntity("m1")));
        when(milestoneService.delete("m1")).thenReturn(Either.right(null));

        ResponseEntity<?> response = milestoneController.deleteMilestone("p1", "m1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // --- helpers ---

    private ProjectEntity projectEntity(String id) {
        return ProjectEntity.builder().id(id).organisationId("org1").fundingId("GRANT-2025-001")
                .externalProjectId("PROJ-AB").projectTitle("Project AB")
                .totalAmount(new BigDecimal("200000.00")).currency("USD").build();
    }

    private MilestoneEntity milestoneEntity(String id) {
        return MilestoneEntity.builder().id(id).milestoneTitle("Milestone AB")
                .milestoneAmount(new BigDecimal("50000.00")).currency("USD")
                .milestoneDate(LocalDate.of(2025, 6, 30)).project(projectEntity("p1")).build();
    }

    private MilestoneView milestoneView(String id) {
        return MilestoneView.builder().milestoneId(id).projectId("p1").milestoneTitle("Milestone AB")
                .milestoneAmount(new BigDecimal("50000.00")).currency("USD")
                .milestoneDate(LocalDate.of(2025, 6, 30)).build();
    }

    private MilestoneCreateRequest createRequest() {
        return MilestoneCreateRequest.builder().milestoneTitle("Milestone AB")
                .milestoneAmount(new BigDecimal("50000.00")).currency("USD")
                .milestoneDate(LocalDate.of(2025, 6, 30)).build();
    }

}
