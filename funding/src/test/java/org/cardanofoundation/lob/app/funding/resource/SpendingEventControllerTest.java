package org.cardanofoundation.lob.app.funding.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingEventCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.service.SpendingEventService;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;

@ExtendWith(MockitoExtension.class)
class SpendingEventControllerTest {

    @Mock
    private SpendingEventService spendingEventService;

    @InjectMocks
    private SpendingEventController spendingEventController;

    // --- listEvents ---

    @Test
    void listEvents_returns200_withList() {
        Pageable pageable = PageRequest.of(0, 10);
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        SpendingEventView view = eventView(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventService.findByOrganisationIdAndFilter(eq("org1"), any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(event)));
        when(spendingEventService.toView(event)).thenReturn(view);

        ResponseEntity<?> response = spendingEventController.listEvents(
                "org1", Optional.empty(), Optional.empty(), pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(List.of(view));
    }

    @Test
    void listEvents_returns200_withEmptyList() {
        Pageable pageable = PageRequest.of(0, 10);
        when(spendingEventService.findByOrganisationIdAndFilter(eq("org1"), any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<?> response = spendingEventController.listEvents(
                "org1", Optional.empty(), Optional.empty(), pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) response.getBody()).isEmpty();
    }

    // --- getEvent ---

    @Test
    void getEvent_returns404_whenNotFound() {
        when(spendingEventService.findById("e1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = spendingEventController.getEvent("e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
    }

    @Test
    void getEvent_returns200_withView() {
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        SpendingEventView view = eventView(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventService.findById("e1")).thenReturn(Optional.of(event));
        when(spendingEventService.toView(event)).thenReturn(view);

        ResponseEntity<?> response = spendingEventController.getEvent("e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- createEvent ---

    @Test
    void createEvent_returns400_whenProjectFieldsMissing() {
        SpendingEventCreateRequest request = createRequest();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Project fields required");
        problem.setTitle(ErrorTitleConstants.PROJECT_FIELDS_REQUIRED);
        when(spendingEventService.create(request)).thenReturn(Either.left(problem));

        ResponseEntity<?> response = spendingEventController.createEvent(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_FIELDS_REQUIRED);
    }

    @Test
    void createEvent_returns201_withView() {
        SpendingEventCreateRequest request = createRequest();
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        SpendingEventView view = eventView(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventService.create(request)).thenReturn(Either.right(event));
        when(spendingEventService.toView(event)).thenReturn(view);

        ResponseEntity<?> response = spendingEventController.createEvent(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- publishEvent ---

    @Test
    void publishEvent_returns404_whenNotFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Event not found");
        problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
        when(spendingEventService.publish("e1")).thenReturn(Either.left(problem));

        ResponseEntity<?> response = spendingEventController.publishEvent("e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
    }

    @Test
    void publishEvent_returns200_withView() {
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.PUBLISHED);
        SpendingEventView view = eventView(EventType.SPENDING, EventStatus.PUBLISHED);
        when(spendingEventService.publish("e1")).thenReturn(Either.right(event));
        when(spendingEventService.toView(event)).thenReturn(view);

        ResponseEntity<?> response = spendingEventController.publishEvent("e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    // --- deleteEvent ---

    @Test
    void deleteEvent_returns404_whenNotFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Event not found");
        problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
        when(spendingEventService.delete("e1")).thenReturn(Either.left(problem));

        ResponseEntity<?> response = spendingEventController.deleteEvent("e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
    }

    @Test
    void deleteEvent_returns409_whenPublished() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Cannot delete a published event");
        problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
        when(spendingEventService.delete("e1")).thenReturn(Either.left(problem));

        ResponseEntity<?> response = spendingEventController.deleteEvent("e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
    }

    @Test
    void deleteEvent_returns204_forDraftEvent() {
        when(spendingEventService.delete("e1")).thenReturn(Either.right(null));

        ResponseEntity<?> response = spendingEventController.deleteEvent("e1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(spendingEventService).delete("e1");
    }

    // --- updateEvent ---

    @Test
    void updateEvent_returns200_withView() {
        SpendingEventCreateRequest request = createRequest();
        FundingEventEntity event = eventEntity(EventType.SPENDING, EventStatus.DRAFT);
        SpendingEventView view = eventView(EventType.SPENDING, EventStatus.DRAFT);
        when(spendingEventService.update("e1", request)).thenReturn(Either.right(event));
        when(spendingEventService.toView(event)).thenReturn(view);

        ResponseEntity<?> response = spendingEventController.updateEvent("e1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void updateEvent_returnsServiceErrorStatus_whenUpdateFails() {
        SpendingEventCreateRequest request = createRequest();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Event not found");
        problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
        when(spendingEventService.update("e1", request)).thenReturn(Either.left(problem));

        ResponseEntity<?> response = spendingEventController.updateEvent("e1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ProblemDetail) response.getBody()).getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
    }

    // --- helpers ---

    private FundingEventEntity eventEntity(EventType type, EventStatus status) {
        return FundingEventEntity.builder()
                .id("e1").eventType(type).status(status)
                .organisationId("org1")
                .fundingId("GRANT-2025-001")
                .currency("USD").totalAmount(BigDecimal.ZERO)
                .build();
    }

    private SpendingEventView eventView(EventType type, EventStatus status) {
        return SpendingEventView.builder()
                .eventId("e1").organisationId("org1").eventType(type).status(status)
                .fundingId("GRANT-2025-001")
                .currency("USD").totalAmount(BigDecimal.ZERO)
                .spendingItems(List.of()).projectAllocations(List.of()).build();
    }

    private SpendingEventCreateRequest createRequest() {
        return SpendingEventCreateRequest.builder()
                .organisationId("org1")
                .eventType(EventType.SPENDING)
                .fundingId("GRANT-2025-001")
                .currency("USD")
                .build();
    }

}
