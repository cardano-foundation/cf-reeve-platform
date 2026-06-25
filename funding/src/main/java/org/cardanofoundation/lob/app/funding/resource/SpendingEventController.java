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
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingEventCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.service.SpendingEventService;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;

@Slf4j
@RestController
@RequestMapping("/api/v1/funding")
@Tag(name = "Events", description = "Funding – Event management API (Funding, Spending, Refund)")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class SpendingEventController {

    private static final String EVENT_NOT_FOUND_DETAIL = "Event not found: ";

    private final SpendingEventService spendingEventService;

    @Operation(summary = "List events for an organisation with optional filters", responses = {
            @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = SpendingEventView.class)))}),
    })
    @GetMapping(value = "/events", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<List<SpendingEventView>> listEvents(
            @RequestParam String organisationId,
            @RequestParam(required = false) Optional<EventStatus> status,
            @RequestParam(required = false) Optional<EventType> eventType,
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {

        List<SpendingEventView> views = spendingEventService
                .findByOrganisationIdAndFilter(organisationId, status, eventType, pageable)
                .getContent()
                .stream()
                .map(spendingEventService::toView)
                .toList();
        return ResponseEntity.ok(views);
    }

    @Operation(summary = "Get a single event by ID", responses = {
            @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SpendingEventView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @GetMapping(value = "/events/{eventId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    @SuppressWarnings("unchecked")
    public ResponseEntity<SpendingEventView> getEvent(@PathVariable String eventId) {
        Optional<FundingEventEntity> eventOpt = spendingEventService.findById(eventId);
        if (eventOpt.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, EVENT_NOT_FOUND_DETAIL + eventId);
            problem.setTitle(ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
            return (ResponseEntity<SpendingEventView>) (ResponseEntity<?>) ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }
        return ResponseEntity.ok(spendingEventService.toView(eventOpt.get()));
    }

    @Operation(
            summary = "Create a new event with project and milestone allocations",
            description = "Creates an event (FUNDING, SPENDING or REFUND) and resolves or creates the referenced " +
                    "projects and milestones in a single request. Supply `projectId` (user-defined project ID) to " +
                    "reference an existing project or create a new one. Supply `milestoneId` (user-defined milestone ID) " +
                    "to reference an existing milestone, or omit it to create a new one.",
            requestBody = @RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SpendingEventCreateRequest.class),
                            examples = {
                                    @ExampleObject(
                                            name = "FUNDING – existing project, existing milestone",
                                            summary = "Allocate funding to an already-created project and milestone",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "FUNDING",
                                                      "fundingId": "GRANT-2025-001",
                                                      "fundingHash": "2736ff28abc...",
                                                      "currency": "USD",
                                                      "allocations": [
                                                        {
                                                          "projectId": "PROJ-AB",
                                                          "milestones": [
                                                            {
                                                              "milestone": { "milestoneId": "MS-1" },
                                                              "allocatedAmount": "50000.00"
                                                            }
                                                          ]
                                                        }
                                                      ],
                                                      "items": []
                                                    }"""
                                    ),
                                    @ExampleObject(
                                            name = "FUNDING – new project, new milestone",
                                            summary = "Allocate funding while creating a new project and milestone on-the-fly",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "FUNDING",
                                                      "fundingId": "GRANT-2025-001",
                                                      "fundingHash": "2736ff28abc...",
                                                      "currency": "USD",
                                                      "allocations": [
                                                        {
                                                          "fundingId": "GRANT-2025-001",
                                                          "projectId": "PROJ-AB",
                                                          "projectTitle": "Project AB",
                                                          "totalAmount": "200000.00",
                                                          "currency": "USD",
                                                          "milestones": [
                                                            {
                                                              "milestone": {
                                                                "milestoneTitle": "Milestone 1",
                                                                "milestoneAmount": "100000.00",
                                                                "currency": "USD",
                                                                "milestoneDate": "2025-12-31"
                                                              },
                                                              "allocatedAmount": "100000.00"
                                                            }
                                                          ]
                                                        }
                                                      ],
                                                      "items": []
                                                    }"""
                                    ),
                                    @ExampleObject(
                                            name = "SPENDING – existing project, existing milestone",
                                            summary = "Record spending against an existing project and milestone",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "SPENDING",
                                                      "fundingId": "GRANT-2025-001",
                                                      "currency": "USD",
                                                      "allocations": [
                                                        {
                                                          "projectId": "PROJ-AB",
                                                          "milestones": [
                                                            {
                                                              "milestone": { "milestoneId": "MS-1" }
                                                            }
                                                          ]
                                                        }
                                                      ],
                                                      "items": [
                                                        {
                                                          "category": "Personnel",
                                                          "vendor": "Vendor AB",
                                                          "amountFcy": "5000.00",
                                                          "currency": "USD",
                                                          "fxRate": "0.85",
                                                          "amountRcy": "4250.00",
                                                          "spendDate": "2025-04-03",
                                                          "hash": "sha256:abc123...",
                                                          "notes": "Invoice #INV-2025-001"
                                                        }
                                                      ]
                                                    }"""
                                    ),
                                    @ExampleObject(
                                            name = "SPENDING – new project, new milestone",
                                            summary = "Record spending while creating project and milestone on-the-fly",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "SPENDING",
                                                      "fundingId": "GRANT-2025-001",
                                                      "currency": "USD",
                                                      "allocations": [
                                                        {
                                                          "fundingId": "GRANT-2025-001",
                                                          "projectId": "PROJ-CD",
                                                          "projectTitle": "Project CD",
                                                          "totalAmount": "100000.00",
                                                          "currency": "USD",
                                                          "milestones": [
                                                            {
                                                              "milestone": {
                                                                "milestoneTitle": "Milestone 2",
                                                                "milestoneAmount": "50000.00",
                                                                "currency": "USD",
                                                                "milestoneDate": "2025-09-30"
                                                              }
                                                            }
                                                          ]
                                                        }
                                                      ],
                                                      "items": [
                                                        {
                                                          "category": "Infrastructure",
                                                          "vendor": "Cloud Co.",
                                                          "amountFcy": "2500.00",
                                                          "currency": "USD",
                                                          "fxRate": "1.00",
                                                          "amountRcy": "2500.00",
                                                          "spendDate": "2025-05-01"
                                                        }
                                                      ]
                                                    }"""
                                    ),
                                    @ExampleObject(
                                            name = "REFUND – existing project, existing milestone",
                                            summary = "Return unspent funds referencing existing entities",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "REFUND",
                                                      "fundingId": "GRANT-2025-001",
                                                      "fundingHash": "refund-tx-hash...",
                                                      "currency": "USD",
                                                      "allocations": [
                                                        {
                                                          "projectId": "PROJ-AB",
                                                          "milestones": [
                                                            {
                                                              "milestone": { "milestoneId": "MS-1" },
                                                              "allocatedAmount": "10000.00"
                                                            }
                                                          ]
                                                        }
                                                      ],
                                                      "items": []
                                                    }"""
                                    ),
                                    @ExampleObject(
                                            name = "REFUND – new project, new milestone",
                                            summary = "Return funds and create project / milestone records in one call",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "REFUND",
                                                      "fundingId": "GRANT-2025-001",
                                                      "fundingHash": "refund-tx-hash...",
                                                      "currency": "USD",
                                                      "allocations": [
                                                        {
                                                          "fundingId": "GRANT-2025-001",
                                                          "projectId": "PROJ-EF",
                                                          "projectTitle": "Project EF",
                                                          "totalAmount": "80000.00",
                                                          "currency": "USD",
                                                          "milestones": [
                                                            {
                                                              "milestone": {
                                                                "milestoneTitle": "Final Milestone",
                                                                "milestoneAmount": "80000.00",
                                                                "currency": "USD",
                                                                "milestoneDate": "2026-03-31"
                                                              },
                                                              "allocatedAmount": "5000.00"
                                                            }
                                                          ]
                                                        }
                                                      ],
                                                      "items": []
                                                    }"""
                                    )
                            }
                    )
            ),
            responses = {
                    @ApiResponse(responseCode = "201", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SpendingEventView.class))}),
                    @ApiResponse(responseCode = "400", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))}),
                    @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))})
            }
    )
    @PostMapping(value = "/events", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) or hasRole(@securityConfig.getAccountantRole())")
    @SuppressWarnings("unchecked")
    public ResponseEntity<SpendingEventView> createEvent(
            @Valid @org.springframework.web.bind.annotation.RequestBody SpendingEventCreateRequest request) {

        Either<ProblemDetail, FundingEventEntity> created = spendingEventService.create(request);
        if (created.isLeft()) {
            return (ResponseEntity<SpendingEventView>) (ResponseEntity<?>) ResponseEntity
                    .status(created.getLeft().getStatus())
                    .body(created.getLeft());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(spendingEventService.toView(created.get()));
    }

    @Operation(summary = "Update a draft event — replaces all allocations from the payload", responses = {
            @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SpendingEventView.class))}),
            @ApiResponse(responseCode = "400", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))}),
            @ApiResponse(responseCode = "409", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @PutMapping(value = "/events/{eventId}", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) or hasRole(@securityConfig.getAccountantRole())")
    @SuppressWarnings("unchecked")
    public ResponseEntity<SpendingEventView> updateEvent(
            @PathVariable String eventId,
            @Valid @org.springframework.web.bind.annotation.RequestBody SpendingEventCreateRequest request) {

        Either<ProblemDetail, FundingEventEntity> updated = spendingEventService.update(eventId, request);
        if (updated.isLeft()) {
            ProblemDetail problem = updated.getLeft();
            return (ResponseEntity<SpendingEventView>) (ResponseEntity<?>) ResponseEntity.status(problem.getStatus()).body(problem);
        }
        return ResponseEntity.ok(spendingEventService.toView(updated.get()));
    }

    @Operation(summary = "Publish an event to the blockchain", responses = {
            @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SpendingEventView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))}),
            @ApiResponse(responseCode = "409", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @PostMapping(value = "/events/{eventId}/publish", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    @SuppressWarnings("unchecked")
    public ResponseEntity<SpendingEventView> publishEvent(@PathVariable String eventId) {
        Either<ProblemDetail, FundingEventEntity> published = spendingEventService.publish(eventId);
        if (published.isLeft()) {
            return (ResponseEntity<SpendingEventView>) (ResponseEntity<?>) ResponseEntity
                    .status(published.getLeft().getStatus())
                    .body(published.getLeft());
        }
        return ResponseEntity.ok(spendingEventService.toView(published.get()));
    }

    @Operation(summary = "Delete a draft event (published events cannot be deleted)", responses = {
            @ApiResponse(responseCode = "204"),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))}),
            @ApiResponse(responseCode = "409", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @DeleteMapping(value = "/events/{eventId}")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> deleteEvent(@PathVariable String eventId) {
        Either<ProblemDetail, Void> deleted = spendingEventService.delete(eventId);
        if (deleted.isLeft()) {
            ProblemDetail problem = deleted.getLeft();
            return (ResponseEntity<Void>) (ResponseEntity<?>) ResponseEntity.status(problem.getStatus()).body(problem);
        }
        return ResponseEntity.noContent().build();
    }

}
