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
                    "projects and milestones in a single request. Supply `externalProjectId` (user-defined project ID) to " +
                    "reference an existing project or create a new one. Supply `externalMilestoneId` (user-defined milestone ID) " +
                    "to reference an existing milestone, or omit it to create a new one.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SpendingEventCreateRequest.class),
                            examples = {
                                    @ExampleObject(
                                            name = "FUNDING – new project, new milestone",
                                            summary = "Create project PROJ-AB and milestone MS-001 on-the-fly while allocating funding",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "FUNDING",
                                                      "fundingId": "GRANT-2025-001",
                                                      "fundingHash": "2736ff28abc1234567890abcdef",
                                                      "fundingEntity": "Cardano Foundation",
                                                      "currency": "USD",
                                                      "allocations": [
                                                        {
                                                          "fundingId": "GRANT-2025-001-AB",
                                                          "externalProjectId": "PROJ-AB",
                                                          "projectTitle": "Project AB",
                                                          "totalAmount": "200000.00",
                                                          "currency": "USD",
                                                          "milestones": [
                                                            {
                                                              "milestone": {
                                                                "externalMilestoneId": "MS-001",
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
                                            name = "FUNDING – existing project, existing milestone",
                                            summary = "Reference PROJ-AB and MS-001 created in the previous FUNDING event",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "FUNDING",
                                                      "fundingId": "GRANT-2025-002",
                                                      "fundingHash": "9a1b2c3d4e5f6789",
                                                      "fundingEntity": "Cardano Foundation",
                                                      "currency": "USD",
                                                      "allocations": [
                                                        {
                                                          "externalProjectId": "PROJ-AB",
                                                          "milestones": [
                                                            {
                                                              "milestone": { "externalMilestoneId": "MS-001" },
                                                              "allocatedAmount": "50000.00"
                                                            }
                                                          ]
                                                        }
                                                      ],
                                                      "items": []
                                                    }"""
                                    ),
                                    @ExampleObject(
                                            name = "SPENDING – new project, new milestone",
                                            summary = "Create project PROJ-CD and milestone MS-001 on-the-fly while recording spending",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "SPENDING",
                                                      "fundingId": "GRANT-2025-001",
                                                      "currency": "USD",
                                                      "allocations": [
                                                        {
                                                          "fundingId": "GRANT-2025-001-CD",
                                                          "externalProjectId": "PROJ-CD",
                                                          "projectTitle": "Project CD",
                                                          "totalAmount": "100000.00",
                                                          "currency": "USD",
                                                          "milestones": [
                                                            {
                                                              "milestone": {
                                                                "externalMilestoneId": "MS-001",
                                                                "milestoneTitle": "Milestone 1",
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
                                            name = "SPENDING – all features (subproject + multi-milestone + items)",
                                            summary = "SPENDING with sub-project WP-1, three milestones, and three spend items",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "SPENDING",
                                                      "fundingId": "GRANT-2025-001",
                                                      "currency": "USD",
                                                      "allocations": [
                                                        {
                                                          "fundingId": "GRANT-2025-001-GH",
                                                          "externalProjectId": "PROJ-GH",
                                                          "projectTitle": "Project GH",
                                                          "totalAmount": "300000.00",
                                                          "currency": "USD",
                                                          "subProject": {
                                                            "subProjectId": "WP-1",
                                                            "projectTitle": "Work Package 1"
                                                          },
                                                          "milestones": [
                                                            {
                                                              "milestone": {
                                                                "externalMilestoneId": "MS-001",
                                                                "milestoneTitle": "Deliverable 1 – Design",
                                                                "milestoneAmount": "50000.00",
                                                                "currency": "USD",
                                                                "milestoneDate": "2025-06-30"
                                                              },
                                                              "allocatedAmount": "50000.00"
                                                            },
                                                            {
                                                              "milestone": {
                                                                "externalMilestoneId": "MS-002",
                                                                "milestoneTitle": "Deliverable 2 – Implementation",
                                                                "milestoneAmount": "100000.00",
                                                                "currency": "USD",
                                                                "milestoneDate": "2025-09-30"
                                                              },
                                                              "allocatedAmount": "100000.00"
                                                            },
                                                            {
                                                              "milestone": {
                                                                "externalMilestoneId": "MS-003",
                                                                "milestoneTitle": "Deliverable 3 – Audit & Closeout",
                                                                "milestoneAmount": "50000.00",
                                                                "currency": "USD",
                                                                "milestoneDate": "2025-12-31"
                                                              },
                                                              "allocatedAmount": "50000.00"
                                                            }
                                                          ]
                                                        }
                                                      ],
                                                      "items": [
                                                        {
                                                          "category": "Personnel",
                                                          "vendor": "Contractor A",
                                                          "amountFcy": "12000.00",
                                                          "currency": "USD",
                                                          "fxRate": "1.00",
                                                          "amountRcy": "12000.00",
                                                          "spendDate": "2025-04-01",
                                                          "hash": "sha256:aabbcc112233",
                                                          "notes": "Invoice #INV-2025-101"
                                                        },
                                                        {
                                                          "category": "Infrastructure",
                                                          "vendor": "Cloud Co.",
                                                          "amountFcy": "3500.00",
                                                          "currency": "USD",
                                                          "fxRate": "1.00",
                                                          "amountRcy": "3500.00",
                                                          "spendDate": "2025-04-15",
                                                          "notes": "Monthly hosting – April 2025"
                                                        },
                                                        {
                                                          "category": "Travel",
                                                          "vendor": "Airline XYZ",
                                                          "amountFcy": "1800.00",
                                                          "currency": "EUR",
                                                          "fxRate": "1.08",
                                                          "amountRcy": "1944.00",
                                                          "spendDate": "2025-05-10",
                                                          "hash": "sha256:ddeeff445566",
                                                          "notes": "Conference travel – receipt attached"
                                                        }
                                                      ]
                                                    }"""
                                    ),
                                    @ExampleObject(
                                            name = "REFUND – new project, new milestone",
                                            summary = "Create project PROJ-EF and milestone MS-001 on-the-fly while recording a refund",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "REFUND",
                                                      "fundingId": "GRANT-2025-001",
                                                      "fundingHash": "refund-tx-hash-abc123",
                                                      "currency": "USD",
                                                      "allocations": [
                                                        {
                                                          "fundingId": "GRANT-2025-001-EF",
                                                          "externalProjectId": "PROJ-EF",
                                                          "projectTitle": "Project EF",
                                                          "totalAmount": "80000.00",
                                                          "currency": "USD",
                                                          "milestones": [
                                                            {
                                                              "milestone": {
                                                                "externalMilestoneId": "MS-001",
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
            @Valid @RequestBody SpendingEventCreateRequest request) {

        Either<ProblemDetail, FundingEventEntity> created = spendingEventService.create(request);
        if (created.isLeft()) {
            return (ResponseEntity<SpendingEventView>) (ResponseEntity<?>) ResponseEntity
                    .status(created.getLeft().getStatus())
                    .body(created.getLeft());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(spendingEventService.toView(created.get()));
    }

    @Operation(
            summary = "Update a draft event — replaces all allocations from the payload",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SpendingEventCreateRequest.class),
                            examples = {
                                    @ExampleObject(
                                            name = "FUNDING – update allocations",
                                            summary = "Replace allocations on an existing FUNDING event",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "FUNDING",
                                                      "fundingId": "GRANT-2025-001",
                                                      "fundingHash": "updated-hash-abc",
                                                      "fundingEntity": "Cardano Foundation",
                                                      "currency": "USD",
                                                      "allocations": [
                                                        {
                                                          "externalProjectId": "PROJ-AB",
                                                          "milestones": [
                                                            {
                                                              "milestone": { "externalMilestoneId": "MS-001" },
                                                              "allocatedAmount": "150000.00"
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
                            schema = @Schema(implementation = ProblemDetail.class))}),
                    @ApiResponse(responseCode = "409", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))})
            }
    )
    @PutMapping(value = "/events/{eventId}", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) or hasRole(@securityConfig.getAccountantRole())")
    @SuppressWarnings("unchecked")
    public ResponseEntity<SpendingEventView> updateEvent(
            @PathVariable String eventId,
            @Valid @RequestBody SpendingEventCreateRequest request) {

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
