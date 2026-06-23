package org.cardanofoundation.lob.app.funding.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.SpendingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingEventCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.service.ProjectService;
import org.cardanofoundation.lob.app.funding.service.SpendingEventService;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;

@ExtendWith(MockitoExtension.class)
class SpendingEventControllerTest {

    @Mock
    private SpendingEventService spendingEventService;
    @Mock
    private ProjectService projectService;

    @InjectMocks
    private SpendingEventController spendingEventController;

    // --- listEvents ---

    @Test
    void listEvents_returns404_whenProjectNotFound() {
        when(projectService.findById("p1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = spendingEventController.listEvents(
                "p1", Optional.empty(), Optional.empty(), PageRequest.of(0, 10));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    @Test
    void listEvents_returns200_withList() {
        Pageable pageable = PageRequest.of(0, 10);
        ProjectEntity project = projectEntity();
        when(projectService.findById("p1")).thenReturn(Optional.of(project));
        SpendingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        SpendingEventView view = eventView(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventService.findByProjectIdAndFilter(eq("p1"), any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(event)));
        when(spendingEventService.toView(event)).thenReturn(view);

        ResponseEntity<?> response = spendingEventController.listEvents(
                "p1", Optional.empty(), Optional.empty(), pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(List.of(view));
    }

    @Test
    void listEvents_returns200_withFilteredList() {
        Pageable pageable = PageRequest.of(0, 10);
        ProjectEntity project = projectEntity();
        when(projectService.findById("p1")).thenReturn(Optional.of(project));
        SpendingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        SpendingEventView view = eventView(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventService.findByProjectIdAndFilter(
                "p1", Optional.of(EventStatus.DRAFT), Optional.of(EventType.SPENDING), pageable))
                .thenReturn(new PageImpl<>(List.of(event)));
        when(spendingEventService.toView(event)).thenReturn(view);

        ResponseEntity<?> response = spendingEventController.listEvents(
                "p1", Optional.of(EventStatus.DRAFT), Optional.of(EventType.SPENDING), pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(List.of(view));
    }

    // --- getEvent ---

    @Test
    void getEvent_returns404_whenNotFound() {
        when(spendingEventService.findById("e1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = spendingEventController.getEvent("p1", "e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
    }

    @Test
    void getEvent_returns200_withView() {
        SpendingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        SpendingEventView view = eventView(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventService.findById("e1")).thenReturn(Optional.of(event));
        when(spendingEventService.toView(event)).thenReturn(view);

        ResponseEntity<?> response = spendingEventController.getEvent("p1", "e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- createEvent ---

    @Test
    void createEvent_returns404_whenProjectNotFound() {
        SpendingEventCreateRequest request = createRequest();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Project not found");
        problem.setTitle(ErrorTitleConstants.PROJECT_NOT_FOUND);
        when(spendingEventService.create("p1", request)).thenReturn(Either.left(problem));

        ResponseEntity<?> response = spendingEventController.createEvent("p1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    @Test
    void createEvent_returns201_withView() {
        SpendingEventCreateRequest request = createRequest();
        SpendingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        SpendingEventView view = eventView(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventService.create("p1", request)).thenReturn(Either.right(event));
        when(spendingEventService.toView(event)).thenReturn(view);

        ResponseEntity<?> response = spendingEventController.createEvent("p1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- publishEvent ---

    @Test
    void publishEvent_returns404_whenNotFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Event not found");
        problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
        when(spendingEventService.publish("e1")).thenReturn(Either.left(problem));

        ResponseEntity<?> response = spendingEventController.publishEvent("p1", "e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
    }

    @Test
    void publishEvent_returns200_withView() {
        SpendingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        SpendingEventView view = eventView(EventType.SPENDING, EventStatus.PUBLISHED);
        when(spendingEventService.publish("e1")).thenReturn(Either.right(event));
        when(spendingEventService.toView(event)).thenReturn(view);

        ResponseEntity<?> response = spendingEventController.publishEvent("p1", "e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- deleteEvent ---

    @Test
    void deleteEvent_returns404_whenNotFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Event not found");
        problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
        when(spendingEventService.delete("e1")).thenReturn(Either.left(problem));

        ResponseEntity<?> response = spendingEventController.deleteEvent("p1", "e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
    }

    @Test
    void deleteEvent_returns409_whenPublished() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Cannot delete a published event");
        problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
        when(spendingEventService.delete("e1")).thenReturn(Either.left(problem));

        ResponseEntity<?> response = spendingEventController.deleteEvent("p1", "e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
    }

    @Test
    void deleteEvent_returns204_forDraftEvent() {
        when(spendingEventService.delete("e1")).thenReturn(Either.right(null));

        ResponseEntity<?> response = spendingEventController.deleteEvent("p1", "e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(spendingEventService).delete("e1");
    }

    // --- updateEvent ---

    @Test
    void updateEvent_returns200_withView() {
        SpendingEventCreateRequest request = createRequest();
        SpendingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        SpendingEventView view = eventView(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventService.update("p1", "e1", request)).thenReturn(Either.right(event));
        when(spendingEventService.toView(event)).thenReturn(view);

        ResponseEntity<?> response = spendingEventController.updateEvent("p1", "e1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void updateEvent_returnsServiceErrorStatus_whenUpdateFails() {
        SpendingEventCreateRequest request = createRequest();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Project not found");
        problem.setTitle(ErrorTitleConstants.PROJECT_NOT_FOUND);
        when(spendingEventService.update("p1", "e1", request)).thenReturn(Either.left(problem));

        ResponseEntity<?> response = spendingEventController.updateEvent("p1", "e1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    @Test
    void updateEvent_returns400_whenMilestoneFieldsMissing() {
        SpendingEventCreateRequest request = createRequest();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Missing milestone fields");
        problem.setTitle("MILESTONE_FIELDS_REQUIRED");
        when(spendingEventService.update("p1", "e1", request)).thenReturn(Either.left(problem));

        ResponseEntity<?> response = spendingEventController.updateEvent("p1", "e1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo("MILESTONE_FIELDS_REQUIRED");
    }

    @Test
    void listEvents_returns200_withEmptyList() {
        Pageable pageable = PageRequest.of(0, 10);
        when(projectService.findById("p1")).thenReturn(Optional.of(projectEntity()));
        when(spendingEventService.findByProjectIdAndFilter(eq("p1"), any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<?> response = spendingEventController.listEvents(
                "p1", Optional.empty(), Optional.empty(), pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) response.getBody()).isEmpty();
    }

    // --- helpers ---

    private ProjectEntity projectEntity() {
        return ProjectEntity.builder()
                .id("p1").organisationId("org1").fundingId("GRANT-2025-001")
                .activityId("PROJ-AB").activityTitle("Project AB")
                .expectedTotalAmount(new BigDecimal("200000.00")).currency("USD").build();
    }

    private SpendingEventEntity eventEntity(EventType type, EventStatus status) {
        return SpendingEventEntity.builder()
                .id("e1").eventType(type).status(status)
                .fundingId("GRANT-2025-001").activityId("PROJ-AB")
                .currency("USD").totalAmount(BigDecimal.ZERO)
                .project(projectEntity()).build();
    }

    private SpendingEventView eventView(EventType type, EventStatus status) {
        return SpendingEventView.builder()
                .eventId("e1").projectId("p1").eventType(type).status(status)
                .fundingId("GRANT-2025-001").activityId("PROJ-AB")
                .currency("USD").totalAmount(BigDecimal.ZERO)
                .spendingItems(List.of()).milestoneAllocations(List.of()).build();
    }

    private SpendingEventCreateRequest createRequest() {
        return SpendingEventCreateRequest.builder()
                .eventType(EventType.SPENDING)
                .fundingId("GRANT-2025-001").activityId("PROJ-AB").currency("USD").build();
    }

}
