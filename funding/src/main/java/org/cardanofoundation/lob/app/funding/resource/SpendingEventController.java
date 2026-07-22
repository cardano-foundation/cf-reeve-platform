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
                                            name = "SPENDING – project tree with sub-projects and milestones",
                                            summary = "Creates project \"project1\" with sub-projects \"sub1\"/\"sub2\" and milestones \"mil1\"/\"mil2\"/\"mil3\" on-the-fly while recording spending",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "SPENDING",
                                                      "fundingId": "1234",
                                                      "fundingHash": "45646",
                                                      "currencyRcy": "EUR",
                                                      "eventDate": "2026-07-17",
                                                      "amountFcy": "20000.00",
                                                      "currencyFcy": "USD",
                                                      "fxRate": "0.5",
                                                      "amountRcy": "10000.00",
                                                      "allocations": [
                                                        {
                                                          "externalProjectId": "47014cf0-4088-4a1f-b3fa-d80903b92b37",
                                                          "projectTitle": "project1",
                                                          "totalAmount": "10000.00",
                                                          "currency": "EUR",
                                                          "subProjects": [
                                                            {
                                                              "externalProjectId": "98b68eb1-ae19-4421-bee2-3259f5aa24ab",
                                                              "projectTitle": "sub1",
                                                              "totalAmount": "5000.00",
                                                              "currency": "EUR",
                                                              "milestones": [
                                                                {
                                                                  "milestone": {
                                                                    "externalMilestoneId": "4f5413fc-88af-43e7-8df5-1dcfade98b59",
                                                                    "milestoneTitle": "mil1",
                                                                    "milestoneAmount": "2500.00",
                                                                    "currency": "EUR",
                                                                    "milestoneDate": "2026-07-17"
                                                                  },
                                                                  "allocatedAmount": "2500.00"
                                                                },
                                                                {
                                                                  "milestone": {
                                                                    "externalMilestoneId": "3c7a48e6-223c-44de-a075-ca41dff63038",
                                                                    "milestoneTitle": "mil2",
                                                                    "milestoneAmount": "2500.00",
                                                                    "currency": "EUR",
                                                                    "milestoneDate": "2026-07-18"
                                                                  },
                                                                  "allocatedAmount": "2500.00"
                                                                }
                                                              ]
                                                            },
                                                            {
                                                              "externalProjectId": "82d7d217-980a-4138-b3ae-871c8c5f7bd8",
                                                              "projectTitle": "sub2",
                                                              "totalAmount": "5000.00",
                                                              "currency": "EUR",
                                                              "milestones": [
                                                                {
                                                                  "milestone": {
                                                                    "externalMilestoneId": "3f3c7c12-5214-4f62-ab1a-ae55cefdaff1",
                                                                    "milestoneTitle": "mil3",
                                                                    "milestoneAmount": "5000.00",
                                                                    "currency": "EUR",
                                                                    "milestoneDate": "2026-07-26"
                                                                  },
                                                                  "allocatedAmount": "5000.00"
                                                                }
                                                              ]
                                                            }
                                                          ]
                                                        }
                                                      ]
                                                    }"""
                                    ),
                                    @ExampleObject(
                                            name = "FUNDING – allocate to the existing project tree",
                                            summary = "Allocates funding to the existing \"project1\" / \"sub1\" / \"sub2\" project tree and its milestones created by the SPENDING example above",
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
                                                          "externalProjectId": "47014cf0-4088-4a1f-b3fa-d80903b92b37",
                                                          "subProjects": [
                                                            {
                                                              "externalProjectId": "98b68eb1-ae19-4421-bee2-3259f5aa24ab",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "externalMilestoneId": "4f5413fc-88af-43e7-8df5-1dcfade98b59" },
                                                                  "allocatedAmount": "2500.00"
                                                                },
                                                                {
                                                                  "milestone": { "externalMilestoneId": "3c7a48e6-223c-44de-a075-ca41dff63038" },
                                                                  "allocatedAmount": "2500.00"
                                                                }
                                                              ]
                                                            },
                                                            {
                                                              "externalProjectId": "82d7d217-980a-4138-b3ae-871c8c5f7bd8",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "externalMilestoneId": "3f3c7c12-5214-4f62-ab1a-ae55cefdaff1" },
                                                                  "allocatedAmount": "5000.00"
                                                                }
                                                              ]
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
