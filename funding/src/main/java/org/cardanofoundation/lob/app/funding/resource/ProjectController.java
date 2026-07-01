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
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.service.ProjectService;

@Slf4j
@RestController
@RequestMapping("/api/v1/funding")
@Tag(name = "Funding", description = "Funding – Manage funding events, milestones and projects")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @Operation(description = "List all projects for an organisation", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PagedResponse.class))}),
    })
    @GetMapping(value = "/projects", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
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
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ProjectView> getProject(@PathVariable String projectId) {
        return Responses.respond(projectService.getProject(projectId), HttpStatus.OK);
    }

    @Operation(description = "List sub-projects of a parent project. Use GET /projects/{id} to fetch a single sub-project.", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PagedResponse.class))})
    })
    @GetMapping(value = "/projects/{parentProjectId}/subprojects", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<PagedResponse<ProjectView>> listSubProjects(
            @PathVariable String parentProjectId,
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        return Responses.respond(projectService.listSubProjects(parentProjectId, pageable), HttpStatus.OK);
    }

    @Operation(description = "Create a new project together with its initial milestones in a single request", responses = {
            @ApiResponse(responseCode = "201", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProjectView.class))})
    })
    @PostMapping(value = "/projects", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ProjectView> createProjectWithMilestones(@Valid @RequestBody ProjectWithMilestonesCreateRequest request) {
        return Responses.respond(projectService.createWithMilestones(request), HttpStatus.CREATED);
    }

    @Operation(description = "Update a project", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProjectView.class))})
    })
    @PutMapping(value = "/projects/{projectId}", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ProjectView> updateProject(
            @PathVariable String projectId,
            @Valid @RequestBody ProjectUpdateRequest request) {
        return Responses.respond(projectService.updateProject(projectId, request), HttpStatus.OK);
    }

    @Operation(description = "Delete a project", responses = {
            @ApiResponse(responseCode = "204"),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @DeleteMapping(value = "/projects/{projectId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ProblemDetail> deleteProject(@PathVariable String projectId) {
        return Responses.respondDelete(projectService.deleteProject(projectId));
    }

}
