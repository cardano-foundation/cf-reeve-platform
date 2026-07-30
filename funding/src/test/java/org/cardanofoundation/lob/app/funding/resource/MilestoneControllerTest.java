package org.cardanofoundation.lob.app.funding.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
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

import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.service.MilestoneService;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;

@ExtendWith(MockitoExtension.class)
class MilestoneControllerTest {

    @Mock
    private MilestoneService milestoneService;

    @InjectMocks
    private MilestoneController milestoneController;

    private static final Pageable PAGEABLE = PageRequest.of(0, 10);

    private MilestoneView milestoneView() {
        return MilestoneView.builder().milestoneId("m1").projectId("p1").milestoneTitle("Milestone AB")
                .milestoneAmount(new BigDecimal("50000.00")).currency("USD").milestoneDate(LocalDate.of(2025, 6, 30)).build();
    }

    private ProblemDetail problem(HttpStatus status, String title) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, "detail");
        p.setTitle(title);
        return p;
    }

    @Test
    void listMilestones_returns200() {
        when(milestoneService.listMilestones("p1", "org1", PAGEABLE))
                .thenReturn(PagedResponse.<MilestoneView>builder().content(List.of(milestoneView())).total(1).build());

        ResponseEntity<?> response = milestoneController.listMilestones("p1", "org1", PAGEABLE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((PagedResponse<?>) response.getBody()).getContent()).hasSize(1);
    }

    @Test
    void listMilestones_propagatesErrorStatus() {
        when(milestoneService.listMilestones("p1", "org1", PAGEABLE))
                .thenReturn(PagedResponse.error(problem(HttpStatus.NOT_FOUND, ErrorTitleConstants.PROJECT_NOT_FOUND)));

        ResponseEntity<?> response = milestoneController.listMilestones("p1", "org1", PAGEABLE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((PagedResponse<?>) response.getBody()).getError().orElseThrow().getTitle())
                .isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    @Test
    void getMilestone_returns200_withView() {
        MilestoneView view = milestoneView();
        when(milestoneService.getMilestone("p1", "m1", "org1")).thenReturn(view);

        ResponseEntity<?> response = milestoneController.getMilestone("p1", "m1", "org1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void getMilestone_returns404_withProblem() {
        when(milestoneService.getMilestone("p1", "m1", "org1"))
                .thenReturn(MilestoneView.error(problem(HttpStatus.NOT_FOUND, ErrorTitleConstants.MILESTONE_NOT_FOUND)));

        ResponseEntity<?> response = milestoneController.getMilestone("p1", "m1", "org1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((MilestoneView) response.getBody()).getError().orElseThrow().getTitle())
                .isEqualTo(ErrorTitleConstants.MILESTONE_NOT_FOUND);
    }

    @Test
    void createMilestone_returns201_withView() {
        MilestoneView view = milestoneView();
        MilestoneCreateRequest request = MilestoneCreateRequest.builder().build();
        when(milestoneService.createMilestone("p1", "org1", request)).thenReturn(view);

        ResponseEntity<?> response = milestoneController.createMilestone("p1", "org1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void updateMilestone_returns200_withView() {
        MilestoneView view = milestoneView();
        MilestoneUpdateRequest request = MilestoneUpdateRequest.builder().milestoneTitle("New").build();
        when(milestoneService.updateMilestone("p1", "m1", "org1", request)).thenReturn(view);

        ResponseEntity<?> response = milestoneController.updateMilestone("p1", "m1", "org1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void deleteMilestone_returns204_whenNoError() {
        when(milestoneService.deleteMilestone("p1", "m1", "org1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = milestoneController.deleteMilestone("p1", "m1", "org1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteMilestone_returns404_withProblem() {
        when(milestoneService.deleteMilestone("p1", "m1", "org1"))
                .thenReturn(Optional.of(problem(HttpStatus.NOT_FOUND, ErrorTitleConstants.MILESTONE_NOT_FOUND)));

        ResponseEntity<?> response = milestoneController.deleteMilestone("p1", "m1", "org1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_NOT_FOUND);
    }

}
