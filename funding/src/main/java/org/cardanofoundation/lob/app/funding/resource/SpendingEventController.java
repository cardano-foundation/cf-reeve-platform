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
                                    ),
                                    @ExampleObject(
                                            name = "FUNDING – fund \"Project Orion\" (see the whole-tree PUT /projects example)",
                                            summary = "Full ERROR-lifecycle walkthrough, step 1: after creating \"Project Orion\" (PRJ-1000) via the "
                                                    + "POST /projects example above, fund it fully — 286,728.71 to Sub 1's milestone and "
                                                    + "418,974.15 to Sub 2's milestone, matching their current budgets exactly. Next steps: "
                                                    + "record a SPENDING event too (next example), then shrink the project with the PUT "
                                                    + "/projects example — both events will flip to ERROR since their allocations no longer "
                                                    + "fit the shrunk milestones — then fix each one with the PUT /events/{eventId} examples "
                                                    + "below to bring them back to DRAFT",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "FUNDING",
                                                      "fundingId": "GRANT-2025-F0001",
                                                      "fundingHash": "30c82819cf06cd9264e2ffd3ba858ebf0a3b0b71ada17b5a11fd6da66f120ce7",
                                                      "fundingEntity": "Cardano Foundation",
                                                      "currencyRcy": "ADA",
                                                      "eventDate": "2026-09-01",
                                                      "amountRcy": "705702.86",
                                                      "allocations": [
                                                        {
                                                          "projectTitle": "Project Orion",
                                                          "subProjects": [
                                                            {
                                                              "projectTitle": "Sub 1",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone 1" },
                                                                  "allocatedAmount": "286728.71"
                                                                }
                                                              ]
                                                            },
                                                            {
                                                              "projectTitle": "Sub 2",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone 1" },
                                                                  "allocatedAmount": "418974.15"
                                                                }
                                                              ]
                                                            }
                                                          ]
                                                        }
                                                      ]
                                                    }"""
                                    ),
                                    @ExampleObject(
                                            name = "SPENDING – record spend against \"Project Orion\"",
                                            summary = "Full ERROR-lifecycle walkthrough, step 2: records 120,000 of actual spend against the "
                                                    + "same two milestones just funded above (well within their current budgets) — 50,000 "
                                                    + "against Sub 1's milestone, 70,000 against Sub 2's — so there are two DRAFT events "
                                                    + "(this one and the FUNDING one above) in the tree when it's shrunk in the next step",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "SPENDING",
                                                      "fundingId": "GRANT-2025-1000",
                                                      "fundingHash": "de68ed7879484aa88442b7932641e72d44013e5719c197ebba90770949877056",
                                                      "currencyRcy": "ADA",
                                                      "eventDate": "2026-09-10",
                                                      "category": "Personnel",
                                                      "vendor": "Vendor AB",
                                                      "amountFcy": "120000.00",
                                                      "currencyFcy": "USD",
                                                      "fxRate": "1.0",
                                                      "amountRcy": "120000.00",
                                                      "hash": "sha256:demo-spend-0001",
                                                      "notes": "Invoice #INV-2026-0001",
                                                      "allocations": [
                                                        {
                                                          "projectTitle": "Project Orion",
                                                          "subProjects": [
                                                            {
                                                              "projectTitle": "Sub 1",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone 1" },
                                                                  "allocatedAmount": "50000.00"
                                                                }
                                                              ]
                                                            },
                                                            {
                                                              "projectTitle": "Sub 2",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone 1" },
                                                                  "allocatedAmount": "70000.00"
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
                                    ),
                                    @ExampleObject(
                                            name = "FUNDING – fix an ERROR event after the project was shrunk",
                                            summary = "Full ERROR-lifecycle walkthrough, step 4a: after \"Project Orion\" is shrunk via the "
                                                    + "whole-tree PUT /projects example (Sub 1's milestone down to 35,000, Sub 2's down to "
                                                    + "55,000), the FUNDING event created above no longer fits (286,728.71/418,974.15 each "
                                                    + "exceed the new amounts) and flips to ERROR. Call this on that event's id with smaller "
                                                    + "allocations that fit the new budgets — the update succeeds and the event resets from "
                                                    + "ERROR back to DRAFT automatically",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "FUNDING",
                                                      "fundingId": "GRANT-2025-F0001",
                                                      "fundingHash": "30c82819cf06cd9264e2ffd3ba858ebf0a3b0b71ada17b5a11fd6da66f120ce7",
                                                      "fundingEntity": "Cardano Foundation",
                                                      "currencyRcy": "ADA",
                                                      "eventDate": "2026-09-01",
                                                      "amountRcy": "50000.00",
                                                      "allocations": [
                                                        {
                                                          "projectTitle": "Project Orion",
                                                          "subProjects": [
                                                            {
                                                              "projectTitle": "Sub 1",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone 1" },
                                                                  "allocatedAmount": "20000.00"
                                                                }
                                                              ]
                                                            },
                                                            {
                                                              "projectTitle": "Sub 2",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone 1" },
                                                                  "allocatedAmount": "30000.00"
                                                                }
                                                              ]
                                                            }
                                                          ]
                                                        }
                                                      ]
                                                    }"""
                                    ),
                                    @ExampleObject(
                                            name = "SPENDING – fix an ERROR event after the project was shrunk",
                                            summary = "Full ERROR-lifecycle walkthrough, step 4b: same fix, for the SPENDING event (its "
                                                    + "50,000/70,000 allocations also no longer fit the shrunk 35,000/55,000 milestones) — "
                                                    + "smaller allocations that fit bring it back to DRAFT too",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "SPENDING",
                                                      "fundingId": "GRANT-2025-1000",
                                                      "fundingHash": "de68ed7879484aa88442b7932641e72d44013e5719c197ebba90770949877056",
                                                      "currencyRcy": "ADA",
                                                      "eventDate": "2026-09-10",
                                                      "category": "Personnel",
                                                      "vendor": "Vendor AB",
                                                      "amountFcy": "40000.00",
                                                      "currencyFcy": "USD",
                                                      "fxRate": "1.0",
                                                      "amountRcy": "40000.00",
                                                      "hash": "sha256:demo-spend-0001",
                                                      "notes": "Invoice #INV-2026-0001 (revised)",
                                                      "allocations": [
                                                        {
                                                          "projectTitle": "Project Orion",
                                                          "subProjects": [
                                                            {
                                                              "projectTitle": "Sub 1",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone 1" },
                                                                  "allocatedAmount": "15000.00"
                                                                }
                                                              ]
                                                            },
                                                            {
                                                              "projectTitle": "Sub 2",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone 1" },
                                                                  "allocatedAmount": "25000.00"
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
