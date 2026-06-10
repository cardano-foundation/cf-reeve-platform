package org.cardanofoundation.lob.app.funding.resource;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.service.ProjectService;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@Slf4j
@RestController
@RequestMapping("/api/v1/spending")
@Tag(name = "Projects", description = "Funding – Project management API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class ProjectController {

    private static final String PROJECT_NOT_FOUND_DETAIL = "Project not found: ";

    private final ProjectService projectService;
    private final OrganisationPublicApiIF organisationPublicApi;


    @Operation(description = "List all projects for an organisation", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = ProjectView.class)))}),
    })
    @GetMapping(value = "/projects", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    @SuppressWarnings("unchecked")
    public ResponseEntity<List<ProjectView>> listProjects(
            @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
            @RequestParam String organisationId,
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {

        Optional<Organisation> orgM = organisationPublicApi.findByOrganisationId(organisationId);

        if (orgM.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(BAD_REQUEST, "Organisation with id: %s not found".formatted(organisationId));
            problem.setTitle("ORGANISATION_NOT_FOUND");
            return (ResponseEntity<List<ProjectView>>) (ResponseEntity<?>) ResponseEntity.badRequest().body(problem);
        }

        List<ProjectView> views = projectService.findByOrganisationId(organisationId, pageable)
                .getContent()
                .stream()
                .map(projectService::toView)
                .toList();
        return ResponseEntity.ok(views);
    }

    @Operation(description = "Get a project by ID", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProjectView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @GetMapping(value = "/projects/{projectId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ProjectView> getProject(@PathVariable String projectId) {
        Optional<ProjectEntity> projectOpt = projectService.findById(projectId);
        if (projectOpt.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND_DETAIL + projectId);
            problem.setTitle(ErrorTitleConstants.PROJECT_NOT_FOUND);
            return (ResponseEntity<ProjectView>) (ResponseEntity<?>) ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }
        return ResponseEntity.ok(projectService.toView(projectOpt.get()));
    }

    @Operation(description = "Create a new project together with its initial milestones in a single request", responses = {
            @ApiResponse(responseCode = "201", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProjectView.class))}),
            @ApiResponse(responseCode = "409", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @PostMapping(value = "/projects", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ProjectView> createProjectWithMilestones(@Valid @RequestBody ProjectWithMilestonesCreateRequest request) {
        if (projectService.existsByOrganisationIdAndActivityId(request.getOrganisationId(), request.getActivityId())) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT, "Project already exists for activityId: " + request.getActivityId());
            problem.setTitle(ErrorTitleConstants.PROJECT_ALREADY_EXISTS);
            return (ResponseEntity<ProjectView>) (ResponseEntity<?>) ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }
        ProjectEntity created = projectService.createWithMilestones(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.toView(created));
    }

    @Operation(description = "Update a project", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProjectView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @PutMapping(value = "/projects/{projectId}", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ProjectView> updateProject(
            @PathVariable String projectId,
            @Valid @RequestBody ProjectUpdateRequest request) {

        Either<ProblemDetail, ProjectEntity> updated = projectService.update(projectId, request);
        if (updated.isLeft()) {
            return (ResponseEntity<ProjectView>) (ResponseEntity<?>) ResponseEntity.status(HttpStatus.NOT_FOUND).body(updated.getLeft());
        }
        return ResponseEntity.ok(projectService.toView(updated.get()));
    }

    @Operation(description = "Delete a project", responses = {
            @ApiResponse(responseCode = "204"),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @DeleteMapping(value = "/projects/{projectId}")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> deleteProject(@PathVariable String projectId) {
        Either<ProblemDetail, Void> deleted = projectService.delete(projectId);
        if (deleted.isLeft()) {
            return (ResponseEntity<Void>) (ResponseEntity<?>) ResponseEntity.status(HttpStatus.NOT_FOUND).body(deleted.getLeft());
        }
        return ResponseEntity.noContent().build();
    }

}
