package org.cardanofoundation.lob.app.funding.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingEventCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.service.SpendingEventService;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;

@ExtendWith(MockitoExtension.class)
class SpendingEventControllerTest {

    @Mock
    private SpendingEventService spendingEventService;

    @InjectMocks
    private SpendingEventController spendingEventController;

    private static final Pageable PAGEABLE = PageRequest.of(0, 10);

    private SpendingEventView eventView() {
        return SpendingEventView.builder().eventId("e1").organisationId("org1").eventType(EventType.SPENDING)
                .status(EventStatus.DRAFT).fundingId("GRANT-2025-001").totalAmount(BigDecimal.ZERO).currency("USD").build();
    }

    private ProblemDetail problem(HttpStatus status, String title) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, "detail");
        p.setTitle(title);
        return p;
    }

    @Test
    void listEvents_returns200() {
        when(spendingEventService.listEvents(eq("org1"), any(), any(), eq(PAGEABLE)))
                .thenReturn(PagedResponse.<SpendingEventView>builder().content(List.of(eventView())).total(1).build());

        ResponseEntity<?> response = spendingEventController.listEvents("org1", Optional.empty(), Optional.empty(), PAGEABLE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((PagedResponse<?>) response.getBody()).getContent()).hasSize(1);
    }

    @Test
    void listEvents_propagatesErrorStatus() {
        when(spendingEventService.listEvents(eq("org1"), any(), any(), eq(PAGEABLE)))
                .thenReturn(PagedResponse.error(problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED")));

        ResponseEntity<?> response = spendingEventController.listEvents("org1", Optional.empty(), Optional.empty(), PAGEABLE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void listEventsByProject_returns200() {
        when(spendingEventService.listEventsByProject(eq("p1"), any(), any(), eq(PAGEABLE)))
                .thenReturn(PagedResponse.<SpendingEventView>builder().content(List.of(eventView())).total(1).build());

        ResponseEntity<?> response = spendingEventController.listEventsByProject("p1", Optional.empty(), Optional.empty(), PAGEABLE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((PagedResponse<?>) response.getBody()).getContent()).hasSize(1);
    }

    @Test
    void eventTypes_returnsEnumNames() {
        ResponseEntity<List<String>> response = spendingEventController.eventTypes();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("FUNDING", "SPENDING", "REFUND");
    }

    @Test
    void eventStatuses_returnsEnumNames() {
        ResponseEntity<List<String>> response = spendingEventController.eventStatuses();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("DRAFT", "PUBLISHED");
    }

    @Test
    void getEvent_returns200_withView() {
        SpendingEventView view = eventView();
        when(spendingEventService.getEvent("e1")).thenReturn(view);

        ResponseEntity<?> response = spendingEventController.getEvent("e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void getEvent_returns404_withProblem() {
        when(spendingEventService.getEvent("e1"))
                .thenReturn(SpendingEventView.error(problem(HttpStatus.NOT_FOUND, ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND)));

        ResponseEntity<?> response = spendingEventController.getEvent("e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((SpendingEventView) response.getBody()).getError().orElseThrow().getTitle())
                .isEqualTo(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
    }

    @Test
    void createEvent_returns201_withView() {
        SpendingEventView view = eventView();
        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder().build();
        when(spendingEventService.createEvent(request)).thenReturn(view);

        ResponseEntity<?> response = spendingEventController.createEvent(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void updateEvent_returns200_withView() {
        SpendingEventView view = eventView();
        SpendingEventCreateRequest request = SpendingEventCreateRequest.builder().build();
        when(spendingEventService.updateEvent("e1", request)).thenReturn(view);

        ResponseEntity<?> response = spendingEventController.updateEvent("e1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void publishEvent_returns200_withView() {
        SpendingEventView view = eventView();
        when(spendingEventService.publishEvent("e1")).thenReturn(view);

        ResponseEntity<?> response = spendingEventController.publishEvent("e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void deleteEvent_returns204_whenNoError() {
        when(spendingEventService.deleteEvent("e1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = spendingEventController.deleteEvent("e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteEvent_returns409_withProblem() {
        when(spendingEventService.deleteEvent("e1"))
                .thenReturn(Optional.of(problem(HttpStatus.CONFLICT, ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED)));

        ResponseEntity<?> response = spendingEventController.deleteEvent("e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
    }

}
