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
import org.cardanofoundation.lob.app.funding.domain.view.FundingIdAvailabilityView;
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
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
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
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
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
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<List<String>> eventTypes() {
        return ResponseEntity.ok(Arrays.stream(EventType.values()).map(Enum::name).toList());
    }

    @Operation(summary = "List the available event statuses", responses = {
            @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = String.class)))})
    })
    @GetMapping(value = "/event-statuses", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<List<String>> eventStatuses() {
        return ResponseEntity.ok(Arrays.stream(EventStatus.values()).map(Enum::name).toList());
    }

    @Operation(
            summary = "Check whether a Funding ID is available for a FUNDING event",
            description = "Real-time check for the UI to call as a Funding ID is entered. Covers Funding IDs across " +
                    "both DRAFT and PUBLISHED FUNDING events (SPENDING/REFUND events are expected to reuse a FUNDING " +
                    "event's Funding ID, so they never count against this check). Pass `excludeEventId` when editing " +
                    "an existing event so its own (unchanged) Funding ID does not flag itself.",
            responses = {
                    @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = FundingIdAvailabilityView.class))}),
                    @ApiResponse(responseCode = "400", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))})
            }
    )
    @GetMapping(value = "/events/funding-id-available", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<FundingIdAvailabilityView> fundingIdAvailable(
            @RequestParam String organisationId,
            @RequestParam String fundingId,
            @RequestParam(required = false) Optional<String> excludeEventId) {
        return Responses.respond(spendingEventService.checkFundingEventIdAvailable(organisationId, fundingId, excludeEventId), HttpStatus.OK);
    }

    @Operation(summary = "Get a single event by ID", responses = {
            @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SpendingEventView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @GetMapping(value = "/events/{eventId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<SpendingEventView> getEvent(@PathVariable String eventId) {
        return Responses.respond(spendingEventService.getEvent(eventId), HttpStatus.OK);
    }

    @Operation(
            summary = "Create a new event with project and milestone allocations",
            description = "Creates an event (FUNDING, SPENDING or REFUND) and resolves or creates the referenced " +
                    "projects, sub-projects and milestones in a single atomic request. Supplying only `projectTitle` " +
                    "(or `milestoneTitle`) references an existing entity and fails with 404 when it does not exist; " +
                    "supplying the creation fields (totalAmount/currency, or milestoneAmount/currency/milestoneDate) " +
                    "creates it on the fly. A project/milestone title only needs to be unique within its own scope " +
                    "(siblings under the same parent project) — the same milestone title may be reused across " +
                    "different sub-projects, as shown below. `externalProjectId`/`externalMilestoneId` are accepted " +
                    "for backward compatibility but no longer used to match or create anything. `fundingEntity` is " +
                    "required for FUNDING events; the spend detail fields are required for SPENDING events.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SpendingEventCreateRequest.class),
                            examples = {
                                    @ExampleObject(
                                            name = "SPENDING – sub-projects with same-named milestones, plus a standalone project with a direct milestone",
                                            summary = "Creates \"Project A\" with sub-projects \"Sub One\"/\"Sub Two\" (each with its own \"Milestone One\"/\"Milestone Two\" — the same titles are fine since they're not siblings), and a separate standalone \"Project B\" with a milestone directly on it (no sub-projects) — all on-the-fly while recording spending",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "SPENDING",
                                                      "fundingId": "1234",
                                                      "fundingHash": "45646",
                                                      "currencyRcy": "EUR",
                                                      "eventDate": "2026-07-17",
                                                      "amountFcy": "10000.00",
                                                      "currencyFcy": "USD",
                                                      "fxRate": "0.5",
                                                      "amountRcy": "5000.00",
                                                      "allocations": [
                                                        {
                                                          "projectTitle": "Project A",
                                                          "totalAmount": "10000.00",
                                                          "currency": "EUR",
                                                          "subProjects": [
                                                            {
                                                              "externalProjectId": "sub-one",
                                                              "projectTitle": "Sub One",
                                                              "totalAmount": "3000.00",
                                                              "currency": "EUR",
                                                              "milestones": [
                                                                {
                                                                  "milestone": {
                                                                    "milestoneTitle": "Milestone One",
                                                                    "milestoneAmount": "1500.00",
                                                                    "currency": "EUR",
                                                                    "milestoneDate": "2026-07-17"
                                                                  },
                                                                  "allocatedAmount": "1000.00"
                                                                },
                                                                {
                                                                  "milestone": {
                                                                    "milestoneTitle": "Milestone Two",
                                                                    "milestoneAmount": "1500.00",
                                                                    "currency": "EUR",
                                                                    "milestoneDate": "2026-07-18"
                                                                  },
                                                                  "allocatedAmount": "1000.00"
                                                                }
                                                              ]
                                                            },
                                                            {
                                                              "externalProjectId": "sub-two",
                                                              "projectTitle": "Sub Two",
                                                              "totalAmount": "3000.00",
                                                              "currency": "EUR",
                                                              "milestones": [
                                                                {
                                                                  "milestone": {
                                                                    "milestoneTitle": "Milestone One",
                                                                    "milestoneAmount": "1500.00",
                                                                    "currency": "EUR",
                                                                    "milestoneDate": "2026-07-17"
                                                                  },
                                                                  "allocatedAmount": "1000.00"
                                                                },
                                                                {
                                                                  "milestone": {
                                                                    "milestoneTitle": "Milestone Two",
                                                                    "milestoneAmount": "1500.00",
                                                                    "currency": "EUR",
                                                                    "milestoneDate": "2026-07-18"
                                                                  },
                                                                  "allocatedAmount": "1000.00"
                                                                }
                                                              ]
                                                            }
                                                          ]
                                                        },
                                                        {
                                                          "projectTitle": "Project B",
                                                          "totalAmount": "2000.00",
                                                          "currency": "EUR",
                                                          "milestones": [
                                                            {
                                                              "milestone": {
                                                                "milestoneTitle": "Milestone One",
                                                                "milestoneAmount": "2000.00",
                                                                "currency": "EUR",
                                                                "milestoneDate": "2026-07-17"
                                                              },
                                                              "allocatedAmount": "1000.00"
                                                            }
                                                          ]
                                                        }
                                                      ]
                                                    }"""
                                    ),
                                    @ExampleObject(
                                            name = "FUNDING – allocate to the existing project tree",
                                            summary = "Allocates funding purely by title to the existing \"Project A\" / \"Sub One\" / \"Sub Two\" / \"Project B\" tree and its milestones created by the SPENDING example above — note the same \"Milestone One\"/\"Milestone Two\" titles resolve to different milestones depending on which project they're nested under",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "FUNDING",
                                                      "fundingId": "1234",
                                                      "fundingHash": "tttt55656",
                                                      "fundingEntity": "1222",
                                                      "currencyRcy": "EUR",
                                                      "eventDate": "2026-07-18",
                                                      "allocations": [
                                                        {
                                                          "projectTitle": "Project A",
                                                          "subProjects": [
                                                            {
                                                              "projectTitle": "Sub One",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone One" },
                                                                  "allocatedAmount": "500.00"
                                                                },
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone Two" },
                                                                  "allocatedAmount": "500.00"
                                                                }
                                                              ]
                                                            },
                                                            {
                                                              "projectTitle": "Sub Two",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone One" },
                                                                  "allocatedAmount": "500.00"
                                                                },
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone Two" },
                                                                  "allocatedAmount": "500.00"
                                                                }
                                                              ]
                                                            }
                                                          ]
                                                        },
                                                        {
                                                          "projectTitle": "Project B",
                                                          "milestones": [
                                                            {
                                                              "milestone": { "milestoneTitle": "Milestone One" },
                                                              "allocatedAmount": "1000.00"
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
                                                      "currencyRcy": "USD",
                                                      "allocations": [
                                                        {
                                                          "projectTitle": "Project AB",
                                                          "milestones": [
                                                            {
                                                              "milestone": { "milestoneTitle": "Milestone AB-1" },
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
