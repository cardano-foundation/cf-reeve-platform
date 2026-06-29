package org.cardanofoundation.lob.app.funding.resource;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

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
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.service.ProjectService;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@Slf4j
@RestController
@RequestMapping("/api/v1/funding")
@Tag(name = "Projects", description = "Funding – Project management API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class ProjectController {

    private static final String PROJECT_NOT_FOUND_DETAIL = "Project not found: ";

    private final KeycloakSecurityHelper keycloakSecurityHelper;
    private final ProjectService projectService;
    private final OrganisationPublicApiIF organisationPublicApi;

    @Operation(description = "List all projects for an organisation", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PagedResponse.class))}),
    })
    @GetMapping(value = "/projects", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> listProjects(
            @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
            @RequestParam String organisationId,
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        if (!keycloakSecurityHelper.canUserAccessOrg(organisationId)) {
            return ResponseEntity.status(UNAUTHORIZED).build();
        }
        Optional<Organisation> orgM = organisationPublicApi.findByOrganisationId(organisationId);
        if (orgM.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(BAD_REQUEST, "Organisation with id: %s not found".formatted(organisationId));
            problem.setTitle("ORGANISATION_NOT_FOUND");
            return ResponseEntity.badRequest().body(problem);
        }
        return ResponseEntity.ok(PagedResponse.of(
                projectService.findByOrganisationId(organisationId, pageable), projectService::toView));
    }

    @Operation(description = "Get a project by ID (also serves sub-projects, which are projects)", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProjectView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @GetMapping(value = "/projects/{projectId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> getProject(@PathVariable String projectId) {
        Optional<ProjectEntity> projectOpt = projectService.findById(projectId);
        if (projectOpt.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND_DETAIL + projectId);
            problem.setTitle(ErrorTitleConstants.PROJECT_NOT_FOUND);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }
        if (!keycloakSecurityHelper.canUserAccessOrg(projectOpt.get().getOrganisationId())) {
            return ResponseEntity.status(UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(projectService.toView(projectOpt.get()));
    }

    @Operation(description = "List sub-projects of a parent project. Use GET /projects/{id} to fetch a single sub-project.", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PagedResponse.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @GetMapping(value = "/projects/{parentProjectId}/subprojects", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> listSubProjects(
            @PathVariable String parentProjectId,
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        Optional<ProjectEntity> parentOpt = projectService.findById(parentProjectId);
        if (parentOpt.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND_DETAIL + parentProjectId);
            problem.setTitle(ErrorTitleConstants.PROJECT_NOT_FOUND);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }
        if (!keycloakSecurityHelper.canUserAccessOrg(parentOpt.get().getOrganisationId())) {
            return ResponseEntity.status(UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(PagedResponse.of(
                projectService.findSubProjects(parentProjectId, pageable), projectService::toView));
    }

    @Operation(description = "Create a new project together with its initial milestones in a single request", responses = {
            @ApiResponse(responseCode = "201", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProjectView.class))}),
            @ApiResponse(responseCode = "409", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @PostMapping(value = "/projects", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> createProjectWithMilestones(@Valid @RequestBody ProjectWithMilestonesCreateRequest request) {
        if (projectService.existsByOrganisationIdAndExternalProjectId(request.getOrganisationId(), request.getExternalProjectId())) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT, "Project already exists for externalProjectId: " + request.getExternalProjectId());
            problem.setTitle(ErrorTitleConstants.PROJECT_ALREADY_EXISTS);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }
        Either<ProblemDetail, ProjectEntity> created = projectService.createWithMilestones(request);
        if (created.isLeft()) {
            return ResponseEntity.status(created.getLeft().getStatus()).body(created.getLeft());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.toView(created.get()));
    }

    @Operation(description = "Update a project", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProjectView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @PutMapping(value = "/projects/{projectId}", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> updateProject(
            @PathVariable String projectId,
            @Valid @RequestBody ProjectUpdateRequest request) {
        Optional<ProjectEntity> byId = projectService.findById(projectId);
        if (byId.isPresent() && !keycloakSecurityHelper.canUserAccessOrg(byId.get().getOrganisationId())) {
            return ResponseEntity.status(UNAUTHORIZED).build();
        }
        Either<ProblemDetail, ProjectEntity> updated = projectService.update(projectId, request);
        if (updated.isLeft()) {
            return ResponseEntity.status(updated.getLeft().getStatus()).body(updated.getLeft());
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
    public ResponseEntity<?> deleteProject(@PathVariable String projectId) {
        Optional<ProjectEntity> byId = projectService.findById(projectId);
        if (byId.isPresent() && !keycloakSecurityHelper.canUserAccessOrg(byId.get().getOrganisationId())) {
            return ResponseEntity.status(UNAUTHORIZED).build();
        }
        Either<ProblemDetail, Void> deleted = projectService.delete(projectId);
        if (deleted.isLeft()) {
            return ResponseEntity.status(deleted.getLeft().getStatus()).body(deleted.getLeft());
        }
        return ResponseEntity.noContent().build();
    }

}
