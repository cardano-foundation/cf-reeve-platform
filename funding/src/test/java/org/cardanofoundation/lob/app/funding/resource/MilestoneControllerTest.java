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

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.service.MilestoneService;
import org.cardanofoundation.lob.app.funding.service.ProjectService;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;

@ExtendWith(MockitoExtension.class)
class MilestoneControllerTest {

    @Mock
    private MilestoneService milestoneService;
    @Mock
    private ProjectService projectService;

    @InjectMocks
    private MilestoneController milestoneController;

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
        assertThat(response.getBody()).isEqualTo(List.of(view));
    }

    // --- getMilestone ---

    @Test
    void getMilestone_returns404_whenNotFound() {
        when(milestoneService.findById("m1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = milestoneController.getMilestone("p1", "m1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_NOT_FOUND);
    }

    @Test
    void getMilestone_returns200_withView() {
        MilestoneEntity milestone = milestoneEntity("m1");
        MilestoneView view = milestoneView("m1");
        when(milestoneService.findById("m1")).thenReturn(Optional.of(milestone));
        when(milestoneService.toView(milestone)).thenReturn(view);

        ResponseEntity<?> response = milestoneController.getMilestone("p1", "m1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- createMilestone ---

    @Test
    void createMilestone_returns404_whenProjectNotFound() {
        MilestoneCreateRequest request = createRequest();
        when(milestoneService.create("p1", request)).thenReturn(Optional.empty());

        ResponseEntity<?> response = milestoneController.createMilestone("p1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    @Test
    void createMilestone_returns201_withView() {
        MilestoneCreateRequest request = createRequest();
        MilestoneEntity milestone = milestoneEntity("m-new");
        MilestoneView view = milestoneView("m-new");
        when(milestoneService.create("p1", request)).thenReturn(Optional.of(milestone));
        when(milestoneService.toView(milestone)).thenReturn(view);

        ResponseEntity<?> response = milestoneController.createMilestone("p1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- updateMilestone ---

    @Test
    void updateMilestone_returns404_whenNotFound() {
        MilestoneUpdateRequest request = MilestoneUpdateRequest.builder().label("New").build();
        when(milestoneService.update("m1", request)).thenReturn(Optional.empty());

        ResponseEntity<?> response = milestoneController.updateMilestone("p1", "m1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_NOT_FOUND);
    }

    @Test
    void updateMilestone_returns200_withView() {
        MilestoneUpdateRequest request = MilestoneUpdateRequest.builder().label("New").build();
        MilestoneEntity milestone = milestoneEntity("m1");
        MilestoneView view = milestoneView("m1");
        when(milestoneService.update("m1", request)).thenReturn(Optional.of(milestone));
        when(milestoneService.toView(milestone)).thenReturn(view);

        ResponseEntity<?> response = milestoneController.updateMilestone("p1", "m1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- deleteMilestone ---

    @Test
    void deleteMilestone_returns404_whenNotFound() {
        when(milestoneService.delete("m1")).thenReturn(false);

        ResponseEntity<?> response = milestoneController.deleteMilestone("p1", "m1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_NOT_FOUND);
    }

    @Test
    void deleteMilestone_returns204_whenDeleted() {
        when(milestoneService.delete("m1")).thenReturn(true);

        ResponseEntity<?> response = milestoneController.deleteMilestone("p1", "m1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // --- helpers ---

    private ProjectEntity projectEntity(String id) {
        return ProjectEntity.builder().id(id).organisationId("org1").fundingId("GRANT-2025-001")
                .activityId("PROJ-AB").activityTitle("Project AB")
                .expectedTotalAmount(new BigDecimal("200000.00")).currency("USD").build();
    }

    private MilestoneEntity milestoneEntity(String id) {
        return MilestoneEntity.builder().id(id).label("Milestone AB")
                .expectedCost(new BigDecimal("50000.00")).currency("USD")
                .dueDate(LocalDate.of(2025, 6, 30)).project(projectEntity("p1")).build();
    }

    private MilestoneView milestoneView(String id) {
        return MilestoneView.builder().milestoneId(id).projectId("p1").label("Milestone AB")
                .expectedCost(new BigDecimal("50000.00")).currency("USD")
                .dueDate(LocalDate.of(2025, 6, 30)).build();
    }

    private MilestoneCreateRequest createRequest() {
        return MilestoneCreateRequest.builder().label("Milestone AB")
                .expectedCost(new BigDecimal("50000.00")).currency("USD")
                .dueDate(LocalDate.of(2025, 6, 30)).build();
    }

}
