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
import org.cardanofoundation.lob.app.funding.domain.view.OrphanEventsCleanupView;
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
                                            summary = "Orion walkthrough, step 2 of 6: after creating \"Project Orion\" (PRJ-1000) with the POST "
                                                    + "/projects example, fund it fully — 286,728.71 to Sub 1's milestone and 418,974.15 to Sub 2's "
                                                    + "milestone, matching their current budgets exactly. Next step in endpoint POST /events: the "
                                                    + "example \"SPENDING – record spend against \"Project Orion\"\".",
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
                                            name = "FUNDING – fund both sub-projects of \"Project Atlas\" (see the delete/orphan PUT /projects example)",
                                            summary = "Atlas walkthrough, step 2 of 6: after creating \"Project Atlas\" (PRJ-2000) with the POST "
                                                    + "/projects example, fund both its sub-projects' milestones from the same event — 20,000 to "
                                                    + "Sub A's, 15,000 to Sub B's. Next step in endpoint POST /events: the example \"SPENDING – "
                                                    + "spend against only \"Sub B\" of \"Project Atlas\"\".",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "FUNDING",
                                                      "fundingId": "GRANT-ATLAS-0001",
                                                      "fundingHash": "atlas-hash-0001",
                                                      "fundingEntity": "Cardano Foundation",
                                                      "currencyRcy": "ADA",
                                                      "eventDate": "2026-09-15",
                                                      "amountRcy": "35000.00",
                                                      "allocations": [
                                                        {
                                                          "projectTitle": "Project Atlas",
                                                          "subProjects": [
                                                            {
                                                              "projectTitle": "Sub A",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone A1" },
                                                                  "allocatedAmount": "20000.00"
                                                                }
                                                              ]
                                                            },
                                                            {
                                                              "projectTitle": "Sub B",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone B1" },
                                                                  "allocatedAmount": "15000.00"
                                                                }
                                                              ]
                                                            }
                                                          ]
                                                        }
                                                      ]
                                                    }"""
                                    ),
                                    @ExampleObject(
                                            name = "SPENDING – spend against only \"Sub B\" of \"Project Atlas\" (becomes an orphan)",
                                            summary = "Atlas walkthrough, step 3 of 6: records 10,000 of spend against Sub B's milestone only "
                                                    + "(unlike the FUNDING event above, which also touches Sub A). Once Sub B is deleted, this "
                                                    + "event has no allocation left that points at an existing milestone. Next step in endpoint PUT "
                                                    + "/projects/{projectId}: the example \"Delete a sub-project\".",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "SPENDING",
                                                      "fundingId": "GRANT-ATLAS-0002",
                                                      "fundingHash": "atlas-hash-0002",
                                                      "currencyRcy": "ADA",
                                                      "eventDate": "2026-09-20",
                                                      "category": "Personnel",
                                                      "vendor": "Vendor Atlas",
                                                      "amountFcy": "10000.00",
                                                      "currencyFcy": "USD",
                                                      "fxRate": "1.0",
                                                      "amountRcy": "10000.00",
                                                      "hash": "sha256:demo-atlas-0002",
                                                      "notes": "Invoice #INV-ATLAS-0002",
                                                      "allocations": [
                                                        {
                                                          "projectTitle": "Project Atlas",
                                                          "subProjects": [
                                                            {
                                                              "projectTitle": "Sub B",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone B1" },
                                                                  "allocatedAmount": "10000.00"
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
                                            summary = "Orion walkthrough, step 3 of 6: records 120,000 of actual spend against the same two "
                                                    + "milestones just funded (well within their current budgets) — 50,000 against Sub 1's "
                                                    + "milestone, 70,000 against Sub 2's — so there are two DRAFT events (this one and the FUNDING "
                                                    + "one) in the tree when it is shrunk. Next step in endpoint PUT /projects/{projectId}: the "
                                                    + "example \"Shrink a project and both its sub-projects together\".",
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
                                            summary = "Orion walkthrough, step 5 of 6: after \"Project Orion\" is shrunk with the whole-tree PUT "
                                                    + "/projects example (Sub 1's milestone down to 35,000, Sub 2's down to 55,000), the FUNDING "
                                                    + "event created above no longer fits (286,728.71/418,974.15 each exceed the new amounts) and "
                                                    + "flips to ERROR. Call this on that event's id with smaller allocations that fit the new "
                                                    + "budgets — the update succeeds and the event resets from ERROR back to DRAFT automatically. "
                                                    + "Next step in endpoint PUT /events/{eventId}: the example \"SPENDING – fix an ERROR event "
                                                    + "after the project was shrunk\".",
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
                                            summary = "Orion walkthrough, step 6 of 6 (last): same fix for the SPENDING event (its 50,000/70,000 "
                                                    + "allocations also no longer fit the shrunk 35,000/55,000 milestones) — smaller allocations "
                                                    + "that fit bring it back to DRAFT too. Nothing further: both events are DRAFT again.",
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
                                    ),
                                    @ExampleObject(
                                            name = "FUNDING – fix an ERROR event after a sub-project was deleted",
                                            summary = "Atlas walkthrough, step 5 of 6: after \"Sub B\" of \"Project Atlas\" is deleted with the PUT "
                                                    + "/projects/{projectId} example, this FUNDING event is in ERROR: its 15,000 allocation to Sub "
                                                    + "B's milestone is still there but dangling (milestoneDeleted = true), next to its 20,000 "
                                                    + "allocation to Sub A's milestone. Call this on that event's id with allocations that only "
                                                    + "reference what still exists (Sub A) and an amountRcy reduced to match — the update replaces "
                                                    + "all allocations, drops the dangling one, and the event resets from ERROR back to DRAFT "
                                                    + "automatically. The SPENDING event has nothing live left to fix. Next step in endpoint DELETE "
                                                    + "/events/orphans: it deletes that one, since none of its allocations points at an existing "
                                                    + "milestone.",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "eventType": "FUNDING",
                                                      "fundingId": "GRANT-ATLAS-0001",
                                                      "fundingHash": "atlas-hash-0001",
                                                      "fundingEntity": "Cardano Foundation",
                                                      "currencyRcy": "ADA",
                                                      "eventDate": "2026-09-15",
                                                      "amountRcy": "20000.00",
                                                      "allocations": [
                                                        {
                                                          "projectTitle": "Project Atlas",
                                                          "subProjects": [
                                                            {
                                                              "projectTitle": "Sub A",
                                                              "milestones": [
                                                                {
                                                                  "milestone": { "milestoneTitle": "Milestone A1" },
                                                                  "allocatedAmount": "20000.00"
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

    @Operation(summary = "Bulk-delete orphaned ERROR events",
            description = "Deletes every ERROR event of this organisation none of whose allocations points at an "
                    + "existing milestone any more — every milestone they referenced was deleted by an earlier "
                    + "project/milestone cascade delete (LOB-2365 follow-up), which keeps the allocation rows so a "
                    + "human decides what happens to the event. This endpoint is that human decision, taken for all "
                    + "such events at once. An ERROR event that still has at least one allocation to an existing "
                    + "milestone is never touched: it can still be fixed with PUT /events/{eventId}. "
                    + "Atlas walkthrough, step 6 of 6 (last): call this with the same organisationId used "
                    + "throughout the walkthrough, after deleting \"Sub B\" of \"Project Atlas\" (PUT "
                    + "/projects/{projectId}) and fixing the FUNDING event by hand (PUT /events/{eventId}) — the "
                    + "SPENDING event (GRANT-ATLAS-0002), whose only allocation was to Sub B's deleted milestone, "
                    + "is deleted here.",
            responses = {
                    @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = OrphanEventsCleanupView.class))}),
                    @ApiResponse(responseCode = "400", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))})
            }
    )
    @DeleteMapping(value = "/events/orphans", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<OrphanEventsCleanupView> deleteOrphanedErrorEvents(@RequestParam String organisationId) {
        return Responses.respond(spendingEventService.deleteOrphanedErrorEvents(organisationId), HttpStatus.OK);
    }

}
