package org.cardanofoundation.lob.app.funding.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.funding.domain.entity.SpendingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingEventCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.service.ProjectService;
import org.cardanofoundation.lob.app.funding.service.SpendingEventService;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;

@Slf4j
@RestController
@RequestMapping("/api/v1/spending")
@Tag(name = "SpendingEvents", description = "Spending – Event management API (Funding, Spending, Refund)")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class SpendingEventController {

    private static final String EVENT_NOT_FOUND_DETAIL = "Event not found: ";

    private final SpendingEventService spendingEventService;
    private final ProjectService projectService;

    @Operation(description = "List events for a project with optional filtering", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = SpendingEventView.class)))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @GetMapping(value = "/projects/{projectId}/events", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<Object> listEvents(
            @PathVariable String projectId,
            @RequestParam(required = false) Optional<EventStatus> status,
            @RequestParam(required = false) Optional<EventType> eventType,
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        if (projectService.findById(projectId).isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, "Project not found: " + projectId);
            problem.setTitle(ErrorTitleConstants.PROJECT_NOT_FOUND);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }
        List<SpendingEventView> views = spendingEventService
                .findByProjectIdAndFilter(projectId, status, eventType, pageable)
                .getContent()
                .stream()
                .map(spendingEventService::toView)
                .toList();
        return ResponseEntity.ok(views);
    }

    @Operation(description = "Get a single event", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SpendingEventView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @GetMapping(value = "/projects/{projectId}/events/{eventId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<Object> getEvent(
            @PathVariable String projectId,
            @PathVariable String eventId) {

        Optional<SpendingEventEntity> eventOpt = spendingEventService.findById(eventId);
        if (eventOpt.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, EVENT_NOT_FOUND_DETAIL + eventId);
            problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }
        return ResponseEntity.ok(spendingEventService.toView(eventOpt.get()));
    }

    @Operation(description = "Create a new spending event (Funding, Spending, or Refund)", responses = {
            @ApiResponse(responseCode = "201", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SpendingEventView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @PostMapping(value = "/projects/{projectId}/events", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<Object> createEvent(
            @PathVariable String projectId,
            @Valid @RequestBody SpendingEventCreateRequest request) {

        Optional<SpendingEventEntity> created = spendingEventService.create(projectId, request);
        if (created.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, "Project not found: " + projectId);
            problem.setTitle(ErrorTitleConstants.PROJECT_NOT_FOUND);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(spendingEventService.toView(created.get()));
    }

    @Operation(description = "Publish an event to the blockchain (sets status to PUBLISHED)", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SpendingEventView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @PostMapping(value = "/projects/{projectId}/events/{eventId}/publish", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<Object> publishEvent(
            @PathVariable String projectId,
            @PathVariable String eventId) {

        Optional<SpendingEventEntity> published = spendingEventService.publish(eventId);
        if (published.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, EVENT_NOT_FOUND_DETAIL + eventId);
            problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }
        return ResponseEntity.ok(spendingEventService.toView(published.get()));
    }

    @Operation(description = "Delete a draft event (published events cannot be deleted)", responses = {
            @ApiResponse(responseCode = "204"),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))}),
            @ApiResponse(responseCode = "409", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @DeleteMapping(value = "/projects/{projectId}/events/{eventId}")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<Object> deleteEvent(
            @PathVariable String projectId,
            @PathVariable String eventId) {

        Optional<SpendingEventEntity> eventOpt = spendingEventService.findById(eventId);
        if (eventOpt.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, EVENT_NOT_FOUND_DETAIL + eventId);
            problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }
        if (eventOpt.get().getStatus() == EventStatus.PUBLISHED) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT, "Cannot delete a published event: " + eventId);
            problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }
        spendingEventService.delete(eventId);
        return ResponseEntity.noContent().build();
    }

}
