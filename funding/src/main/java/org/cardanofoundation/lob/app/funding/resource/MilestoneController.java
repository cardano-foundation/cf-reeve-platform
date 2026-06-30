package org.cardanofoundation.lob.app.funding.resource;

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
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.service.MilestoneService;
import org.cardanofoundation.lob.app.funding.service.ProjectService;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@Slf4j
@RestController
@RequestMapping("/api/v1/funding")
@Tag(name = "Funding", description = "Funding – Manage funding events, milestones and projects")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class MilestoneController {

    private static final String MILESTONE_NOT_FOUND_DETAIL = "Milestone not found: ";
    private static final String PROJECT_NOT_FOUND_DETAIL = "Project not found: ";

    private final MilestoneService milestoneService;
    private final ProjectService projectService;
    private final KeycloakSecurityHelper keycloakSecurityHelper;

    @Operation(description = "List milestones for a project", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PagedResponse.class))})
    })
    @GetMapping(value = "/projects/{projectId}/milestones", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> listMilestones(
            @PathVariable String projectId,
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        ResponseEntity<?> denied = authorizeProject(projectId);
        if (denied != null) {
            return denied;
        }
        return ResponseEntity.ok(PagedResponse.of(
                milestoneService.findByProjectId(projectId, pageable), milestoneService::toView));
    }

    @Operation(description = "Get a single milestone", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = MilestoneView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @GetMapping(value = "/projects/{projectId}/milestones/{milestoneId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> getMilestone(
            @PathVariable String projectId,
            @PathVariable String milestoneId) {
        ResponseEntity<?> denied = authorizeProject(projectId);
        if (denied != null) {
            return denied;
        }
        Optional<MilestoneEntity> milestoneOpt = milestoneService.findByIdAndProjectId(milestoneId, projectId);
        if (milestoneOpt.isEmpty()) {
            return milestoneNotFound(milestoneId);
        }
        return ResponseEntity.ok(milestoneService.toView(milestoneOpt.get()));
    }

    @Operation(description = "Create a milestone for a project", responses = {
            @ApiResponse(responseCode = "201", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = MilestoneView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @PostMapping(value = "/projects/{projectId}/milestones", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> createMilestone(
            @PathVariable String projectId,
            @Valid @RequestBody MilestoneCreateRequest request) {
        ResponseEntity<?> denied = authorizeProject(projectId);
        if (denied != null) {
            return denied;
        }
        Either<ProblemDetail, MilestoneEntity> created = milestoneService.create(projectId, request);
        if (created.isLeft()) {
            return ResponseEntity.status(created.getLeft().getStatus()).body(created.getLeft());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(milestoneService.toView(created.get()));
    }

    @Operation(description = "Update a milestone", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = MilestoneView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @PutMapping(value = "/projects/{projectId}/milestones/{milestoneId}", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> updateMilestone(
            @PathVariable String projectId,
            @PathVariable String milestoneId,
            @Valid @RequestBody MilestoneUpdateRequest request) {
        ResponseEntity<?> denied = authorizeProject(projectId);
        if (denied != null) {
            return denied;
        }
        if (milestoneService.findByIdAndProjectId(milestoneId, projectId).isEmpty()) {
            return milestoneNotFound(milestoneId);
        }
        Either<ProblemDetail, MilestoneEntity> updated = milestoneService.update(milestoneId, request);
        if (updated.isLeft()) {
            return ResponseEntity.status(updated.getLeft().getStatus()).body(updated.getLeft());
        }
        return ResponseEntity.ok(milestoneService.toView(updated.get()));
    }

    @Operation(description = "Delete a milestone", responses = {
            @ApiResponse(responseCode = "204"),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @DeleteMapping(value = "/projects/{projectId}/milestones/{milestoneId}")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> deleteMilestone(
            @PathVariable String projectId,
            @PathVariable String milestoneId) {
        ResponseEntity<?> denied = authorizeProject(projectId);
        if (denied != null) {
            return denied;
        }
        if (milestoneService.findByIdAndProjectId(milestoneId, projectId).isEmpty()) {
            return milestoneNotFound(milestoneId);
        }
        Either<ProblemDetail, Void> deleted = milestoneService.delete(milestoneId);
        if (deleted.isLeft()) {
            return ResponseEntity.status(deleted.getLeft().getStatus()).body(deleted.getLeft());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Authorizes access to the path project: the project must exist (fail-closed 404 otherwise) and
     * its organisation must be accessible to the caller (401 otherwise). Returns {@code null} when
     * access is granted. A milestone's organisation is its project's, so milestone endpoints scope
     * their access to the project and additionally confirm the milestone belongs to it.
     */
    private ResponseEntity<?> authorizeProject(String projectId) {
        Optional<ProjectEntity> projectOpt = projectService.findById(projectId);
        if (projectOpt.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND_DETAIL + projectId);
            problem.setTitle(ErrorTitleConstants.PROJECT_NOT_FOUND);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }
        if (!keycloakSecurityHelper.canUserAccessOrg(projectOpt.get().getOrganisationId())) {
            return ResponseEntity.status(UNAUTHORIZED).build();
        }
        return null;
    }

    private ResponseEntity<?> milestoneNotFound(String milestoneId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, MILESTONE_NOT_FOUND_DETAIL + milestoneId);
        problem.setTitle(ErrorTitleConstants.MILESTONE_NOT_FOUND);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

}
