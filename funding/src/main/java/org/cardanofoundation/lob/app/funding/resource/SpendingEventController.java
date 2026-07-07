package org.cardanofoundation.lob.app.funding.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import java.util.Arrays;
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

import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingEventCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.service.SpendingEventService;

@Slf4j
@RestController
@RequestMapping("/api/v1/funding")
@Tag(name = "Funding", description = "Funding – Manage funding events, milestones and projects")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class SpendingEventController {

    private final SpendingEventService spendingEventService;

    @Operation(summary = "List events for an organisation with optional filters", responses = {
            @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PagedResponse.class))}),
    })
    @GetMapping(value = "/events", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<PagedResponse<SpendingEventView>> listEvents(
            @RequestParam String organisationId,
            @RequestParam(required = false) Optional<EventStatus> status,
            @RequestParam(required = false) Optional<EventType> eventType,
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        return Responses.respond(spendingEventService.listEvents(organisationId, status, eventType, pageable), HttpStatus.OK);
    }

    @Operation(summary = "List events for a project with optional filters", responses = {
            @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PagedResponse.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @GetMapping(value = "/projects/{projectId}/events", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<PagedResponse<SpendingEventView>> listEventsByProject(
            @PathVariable String projectId,
            @RequestParam(required = false) Optional<EventStatus> status,
            @RequestParam(required = false) Optional<EventType> eventType,
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        return Responses.respond(spendingEventService.listEventsByProject(projectId, status, eventType, pageable), HttpStatus.OK);
    }

    @Operation(summary = "List the available event types", responses = {
            @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = String.class)))})
    })
    @GetMapping(value = "/event-types", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<List<String>> eventTypes() {
        return ResponseEntity.ok(Arrays.stream(EventType.values()).map(Enum::name).toList());
    }

    @Operation(summary = "List the available event statuses", responses = {
            @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = String.class)))})
    })
    @GetMapping(value = "/event-statuses", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<List<String>> eventStatuses() {
        return ResponseEntity.ok(Arrays.stream(EventStatus.values()).map(Enum::name).toList());
    }

    @Operation(summary = "Get a single event by ID", responses = {
            @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SpendingEventView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @GetMapping(value = "/events/{eventId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<SpendingEventView> getEvent(@PathVariable String eventId) {
        return Responses.respond(spendingEventService.getEvent(eventId), HttpStatus.OK);
    }

    @Operation(
            summary = "Create a new event with project and milestone allocations",
            description = "Creates an event (FUNDING, SPENDING or REFUND) and resolves or creates the referenced " +
                    "projects, sub-projects and milestones in a single atomic request. Supplying only an id " +
                    "(`externalProjectId` / `externalMilestoneId`) references an existing entity and fails with 404 " +
                    "when it does not exist; supplying the creation fields (title, amounts, ...) creates it on the fly. " +
                    "`fundingEntity` is required for FUNDING events; the spend detail fields are required for SPENDING events.",
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
                                                                "milestoneDate": "2026-12-31"
                                                              },
                                                              "allocatedAmount": "100000.00"
                                                            }
                                                          ]
                                                        }
                                                      ]
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
                                                      ]
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
                                                      "category": "Infrastructure",
                                                      "vendor": "Cloud Co.",
                                                      "amountFcy": "2500.00",
                                                      "spendCurrency": "USD",
                                                      "fxRate": "1.00",
                                                      "amountRcy": "2500.00",
                                                      "spendDate": "2026-05-01",
                                                      "notes": "Monthly hosting",
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
                                                                "milestoneDate": "2026-09-30"
                                                              },
                                                              "allocatedAmount": "2500.00"
                                                            }
                                                          ]
                                                        }
                                                      ]
                                                    }"""
                                    ),
                                    @ExampleObject(
                                            name = "SPENDING – sub-project tree + multi-milestone",
                                            summary = "SPENDING creating sub-project WP-1 with three milestones on-the-fly",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "SPENDING",
                                                      "fundingId": "GRANT-2025-001",
                                                      "currency": "USD",
                                                      "category": "Personnel",
                                                      "vendor": "Contractor A",
                                                      "amountFcy": "200000.00",
                                                      "spendCurrency": "USD",
                                                      "fxRate": "1.00",
                                                      "amountRcy": "200000.00",
                                                      "spendDate": "2026-04-01",
                                                      "hash": "sha256:aabbcc112233",
                                                      "notes": "Invoice #INV-2026-101",
                                                      "allocations": [
                                                        {
                                                          "fundingId": "GRANT-2025-001-GH",
                                                          "externalProjectId": "PROJ-GH",
                                                          "projectTitle": "Project GH",
                                                          "totalAmount": "300000.00",
                                                          "currency": "USD",
                                                          "subProjects": [
                                                            {
                                                              "externalProjectId": "WP-1",
                                                              "projectTitle": "Work Package 1",
                                                              "totalAmount": "200000.00",
                                                              "currency": "USD",
                                                              "milestones": [
                                                                {
                                                                  "milestone": {
                                                                    "externalMilestoneId": "MS-001",
                                                                    "milestoneTitle": "Deliverable 1 – Design",
                                                                    "milestoneAmount": "50000.00",
                                                                    "currency": "USD",
                                                                    "milestoneDate": "2026-06-30"
                                                                  },
                                                                  "allocatedAmount": "50000.00"
                                                                },
                                                                {
                                                                  "milestone": {
                                                                    "externalMilestoneId": "MS-002",
                                                                    "milestoneTitle": "Deliverable 2 – Implementation",
                                                                    "milestoneAmount": "100000.00",
                                                                    "currency": "USD",
                                                                    "milestoneDate": "2026-09-30"
                                                                  },
                                                                  "allocatedAmount": "100000.00"
                                                                },
                                                                {
                                                                  "milestone": {
                                                                    "externalMilestoneId": "MS-003",
                                                                    "milestoneTitle": "Deliverable 3 – Audit & Closeout",
                                                                    "milestoneAmount": "50000.00",
                                                                    "currency": "USD",
                                                                    "milestoneDate": "2026-12-31"
                                                                  },
                                                                  "allocatedAmount": "50000.00"
                                                                }
                                                              ]
                                                            }
                                                          ]
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
                                                                "milestoneDate": "2026-12-31"
                                                              },
                                                              "allocatedAmount": "5000.00"
                                                            }
                                                          ]
                                                        }
                                                      ]
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
    public ResponseEntity<SpendingEventView> createEvent(
            @Valid @RequestBody SpendingEventCreateRequest request) {
        return Responses.respond(spendingEventService.createEvent(request), HttpStatus.CREATED);
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
                                                      ]
                                                    }"""
                                    )
                            }
                    )
            ),
            responses = {
                    @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
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
    public ResponseEntity<SpendingEventView> updateEvent(
            @PathVariable String eventId,
            @Valid @RequestBody SpendingEventCreateRequest request) {
        return Responses.respond(spendingEventService.updateEvent(eventId, request), HttpStatus.OK);
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
    public ResponseEntity<SpendingEventView> publishEvent(@PathVariable String eventId) {
        return Responses.respond(spendingEventService.publishEvent(eventId), HttpStatus.OK);
    }

    @Operation(summary = "Delete a draft event (published events cannot be deleted)", responses = {
            @ApiResponse(responseCode = "204"),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))}),
            @ApiResponse(responseCode = "409", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @DeleteMapping(value = "/events/{eventId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ProblemDetail> deleteEvent(@PathVariable String eventId) {
        return Responses.respondDelete(spendingEventService.deleteEvent(eventId));
    }

}
