package org.cardanofoundation.lob.app.funding.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.CascadeDeletionView;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectDraftStatusView;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.service.ProjectService;
import org.cardanofoundation.lob.app.funding.service.ProjectTreeUpdateService;

@Slf4j
@RestController
@RequestMapping("/api/v1/funding")
@Tag(name = "Funding", description = "Funding – Manage funding events, milestones and projects")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectTreeUpdateService projectTreeUpdateService;

    @Operation(description = "List all projects for an organisation", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PagedResponse.class))}),
    })
    @GetMapping(value = "/projects", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<PagedResponse<ProjectView>> listProjects(
            @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
            @RequestParam String organisationId,
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        return Responses.respond(projectService.listProjects(organisationId, pageable), HttpStatus.OK);
    }

    @Operation(description = "Get a project by ID (also serves sub-projects, which are projects)", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProjectView.class))})
    })
    @GetMapping(value = "/projects/{projectId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ProjectView> getProject(@PathVariable String projectId) {
        return Responses.respond(projectService.getProject(projectId), HttpStatus.OK);
    }

    @Operation(description = "List sub-projects of a parent project. Use GET /projects/{id} to fetch a single sub-project.", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PagedResponse.class))})
    })
    @GetMapping(value = "/projects/{parentProjectId}/subprojects", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<PagedResponse<ProjectView>> listSubProjects(
            @PathVariable String parentProjectId,
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        return Responses.respond(projectService.listSubProjects(parentProjectId, pageable), HttpStatus.OK);
    }

    @Operation(
            summary = "Create a new project together with its initial milestones in a single request",
            description = "Creates a project (itself, plus every sub-project and milestone under it) in a single "
                    + "atomic request. `proId` is mandatory for the root project (permanent identifier, frozen "
                    + "forever after) — a sub-project's/milestone's proId is always system-assigned "
                    + "(`<parent's proId>-<n>`) unless explicitly supplied.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProjectWithMilestonesCreateRequest.class),
                            examples = @ExampleObject(
                                    name = "Project with two sub-projects, each with a milestone",
                                    summary = "Creates \"Project Orion\" (proId PRJ-1000) with two sub-projects "
                                            + "\"Sub 1\"/\"Sub 2\" (proId PRJ-1000-1/PRJ-1000-2), each with its own "
                                            + "milestone — the same shape used by the whole-tree PUT endpoint below, "
                                            + "so a project created this way can later be resized the same way it "
                                            + "was created",
                                    value = """
                                            {
                                              "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                              "externalProjectId": "PROJ-ORION",
                                              "projectTitle": "Project Orion",
                                              "proId": "PRJ-1000",
                                              "totalAmount": "705702.86",
                                              "currency": "ADA",
                                              "subProjects": [
                                                {
                                                  "externalProjectId": "PROJ-ORION-1",
                                                  "projectTitle": "Sub 1",
                                                  "proId": "PRJ-1000-1",
                                                  "totalAmount": "286728.71",
                                                  "milestones": [
                                                    {
                                                      "proId": "PRJ-1000-1-1",
                                                      "milestoneTitle": "Milestone 1",
                                                      "milestoneAmount": "286728.71",
                                                      "currency": "ADA",
                                                      "milestoneDate": "2026-10-08"
                                                    }
                                                  ]
                                                },
                                                {
                                                  "externalProjectId": "PROJ-ORION-2",
                                                  "projectTitle": "Sub 2",
                                                  "proId": "PRJ-1000-2",
                                                  "totalAmount": "418974.15",
                                                  "milestones": [
                                                    {
                                                      "proId": "PRJ-1000-2-1",
                                                      "milestoneTitle": "Milestone 1",
                                                      "milestoneAmount": "418974.15",
                                                      "currency": "ADA",
                                                      "milestoneDate": "2026-09-24"
                                                    }
                                                  ]
                                                }
                                              ]
                                            }"""
                            )
                    )
            ),
            responses = {
                    @ApiResponse(responseCode = "201", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProjectView.class))})
            })
    @PostMapping(value = "/projects", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ProjectView> createProjectWithMilestones(@Valid @RequestBody ProjectWithMilestonesCreateRequest request) {
        return Responses.respond(projectService.createWithMilestones(request), HttpStatus.CREATED);
    }

    @Operation(
            summary = "Update an existing root project's whole structure in one atomic call",
            description = "Updates the project (itself, plus every sub-project and milestone under it) in one "
                    + "atomic call, identified by the internal id in the URL — projectId must name a root project "
                    + "(no parent); use GET /projects/{id} first if you only have a proId. The whole tree is "
                    + "validated as a single unit before anything is persisted; if the new totals don't add up at "
                    + "any level, nothing is saved. This is a full replacement of the project's own fields "
                    + "(totalAmount/currency are always required, exactly like PUT /events/{eventId}), so a plain "
                    + "rename still needs the current total resent. A sub-project/milestone present in the request "
                    + "but not matched by proId (falling back to its title) is created; one already existing but "
                    + "left out of the request is untouched. One matched by proId/title with its `action` field set "
                    + "to `DELETE` is deleted instead — for a sub-project, its entire subtree (every nested "
                    + "sub-project and milestone below it) is removed with it, regardless of what that node's own "
                    + "milestones/subProjects list additionally contains (LOB-2365 follow-up). Moving a project to "
                    + "a different parent tree is not supported here — delete and recreate it under the new parent "
                    + "instead.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProjectWithMilestonesCreateRequest.class),
                            examples = {
                                    @ExampleObject(
                                            name = "Shrink a project and both its sub-projects together",
                                            summary = "Full ERROR-lifecycle walkthrough, step 3: identify \"Project Orion\" by the "
                                                    + "internal id the POST /projects example returned (in the URL path, not the body — "
                                                    + "proId PRJ-1000 shown here only for readability) and resize it from 705,702.86 down "
                                                    + "to 100,000, while shrinking its two sub-projects (and their milestones) to fit — "
                                                    + "impossible to do safely one field at a time, since shrinking any one of them first "
                                                    + "would be rejected against the others' still-old, larger totals. The FUNDING/SPENDING "
                                                    + "events funded/spent above no longer fit these smaller milestones and flip to ERROR — "
                                                    + "fix them with the PUT /events/{eventId} examples below",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "externalProjectId": "PROJ-ORION",
                                                      "projectTitle": "Project Orion",
                                                      "proId": "PRJ-1000",
                                                      "totalAmount": "100000.00",
                                                      "currency": "ADA",
                                                      "subProjects": [
                                                        {
                                                          "externalProjectId": "PROJ-ORION-1",
                                                          "projectTitle": "Sub 1",
                                                          "proId": "PRJ-1000-1",
                                                          "totalAmount": "40000.00",
                                                          "milestones": [
                                                            {
                                                              "proId": "PRJ-1000-1-1",
                                                              "milestoneTitle": "Milestone 1",
                                                              "milestoneAmount": "35000.00",
                                                              "currency": "ADA",
                                                              "milestoneDate": "2026-10-08"
                                                            }
                                                          ]
                                                        },
                                                        {
                                                          "externalProjectId": "PROJ-ORION-2",
                                                          "projectTitle": "Sub 2",
                                                          "proId": "PRJ-1000-2",
                                                          "totalAmount": "60000.00",
                                                          "milestones": [
                                                            {
                                                              "proId": "PRJ-1000-2-1",
                                                              "milestoneTitle": "Milestone 1",
                                                              "milestoneAmount": "55000.00",
                                                              "currency": "ADA",
                                                              "milestoneDate": "2026-09-24"
                                                            }
                                                          ]
                                                        }
                                                      ]
                                                    }"""
                                    ),
                                    @ExampleObject(
                                            name = "Create, update and delete sub-projects in the same call (standalone example)",
                                            summary = "Not part of the ERROR-lifecycle walkthrough above — a separate demo of a project in "
                                                    + "a different state, identified by its own internal id in the URL path (proId PRJ-1000 "
                                                    + "shown here only for readability): \"Sub 1\" (PRJ-1000-1) is resized down — an update, "
                                                    + "matched by proId; \"Sub 2\" (PRJ-1000-2) is removed entirely (action DELETE — subtree "
                                                    + "and all, including its own milestone); a brand new \"Sub 3\" is added, with no "
                                                    + "matching proId so it's created. One PUT does all three at once, with the root's own "
                                                    + "total shrunk to match what remains (Sub 1 + Sub 3)",
                                            value = """
                                                    {
                                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                                      "externalProjectId": "PROJ-ORION",
                                                      "projectTitle": "Project Orion",
                                                      "proId": "PRJ-1000",
                                                      "totalAmount": "60000.00",
                                                      "currency": "ADA",
                                                      "subProjects": [
                                                        {
                                                          "externalProjectId": "PROJ-ORION-1",
                                                          "projectTitle": "Sub 1",
                                                          "proId": "PRJ-1000-1",
                                                          "totalAmount": "40000.00",
                                                          "milestones": [
                                                            {
                                                              "proId": "PRJ-1000-1-1",
                                                              "milestoneTitle": "Milestone 1",
                                                              "milestoneAmount": "35000.00",
                                                              "currency": "ADA",
                                                              "milestoneDate": "2026-10-08"
                                                            }
                                                          ]
                                                        },
                                                        {
                                                          "externalProjectId": "PROJ-ORION-2",
                                                          "projectTitle": "Sub 2",
                                                          "proId": "PRJ-1000-2",
                                                          "action": "DELETE"
                                                        },
                                                        {
                                                          "externalProjectId": "PROJ-ORION-3",
                                                          "projectTitle": "Sub 3",
                                                          "totalAmount": "20000.00",
                                                          "milestones": [
                                                            {
                                                              "milestoneTitle": "Milestone 1",
                                                              "milestoneAmount": "20000.00",
                                                              "currency": "ADA",
                                                              "milestoneDate": "2026-11-01"
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
                    @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProjectView.class))})
            })
    @PutMapping(value = "/projects/{projectId}", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ProjectView> updateProject(
            @PathVariable String projectId,
            @Valid @RequestBody ProjectWithMilestonesCreateRequest request) {
        return Responses.respond(projectTreeUpdateService.updateWithMilestones(projectId, request), HttpStatus.OK);
    }

    @Operation(description = "Whether this project has at least one linked event still in Draft status, "
            + "anywhere in its own structure — the edit flow calls this before opening the edit form to "
            + "decide whether to show the draft warning.", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProjectDraftStatusView.class))})
    })
    @GetMapping(value = "/projects/{projectId}/has-draft-event", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ProjectDraftStatusView> hasDraftEvent(
            @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
            @RequestParam String organisationId,
            @PathVariable String projectId) {
        return Responses.respond(projectService.hasDraftEvent(organisationId, projectId), HttpStatus.OK);
    }

    @Operation(description = "Delete a project. On success, lists any non-published events that had an "
            + "allocation removed by this delete and were flagged ERROR as a result (LOB-2365 follow-up) — "
            + "the events themselves are not deleted.", responses = {
            @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CascadeDeletionView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @DeleteMapping(value = "/projects/{projectId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<CascadeDeletionView> deleteProject(@PathVariable String projectId) {
        return Responses.respond(projectService.deleteProject(projectId), HttpStatus.OK);
    }

}
