package org.cardanofoundation.lob.app.funding.resource;

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
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.service.MilestoneService;
import org.cardanofoundation.lob.app.funding.service.ProjectService;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;

@Slf4j
@RestController
@RequestMapping("/api/v1/spending")
@Tag(name = "Milestones", description = "Spending – Milestone management API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class MilestoneController {

    private static final String MILESTONE_NOT_FOUND_DETAIL = "Milestone not found: ";

    private final MilestoneService milestoneService;
    private final ProjectService projectService;

    @Operation(description = "List milestones for a project", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = MilestoneView.class)))})
    })
    @GetMapping(value = "/projects/{projectId}/milestones", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<Object> listMilestones(
            @PathVariable String projectId,
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        if (projectService.findById(projectId).isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, "Project not found: " + projectId);
            problem.setTitle(ErrorTitleConstants.PROJECT_NOT_FOUND);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }
        List<MilestoneView> views = milestoneService.findByProjectId(projectId, pageable)
                .getContent()
                .stream()
                .map(milestoneService::toView)
                .toList();
        return ResponseEntity.ok(views);
    }

    @Operation(description = "Get a single milestone", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = MilestoneView.class))}),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @GetMapping(value = "/projects/{projectId}/milestones/{milestoneId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<Object> getMilestone(
            @PathVariable String projectId,
            @PathVariable String milestoneId) {

        Optional<MilestoneEntity> milestoneOpt = milestoneService.findById(milestoneId);
        if (milestoneOpt.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, MILESTONE_NOT_FOUND_DETAIL + milestoneId);
            problem.setTitle(ErrorTitleConstants.MILESTONE_NOT_FOUND);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
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
    public ResponseEntity<Object> createMilestone(
            @PathVariable String projectId,
            @Valid @RequestBody MilestoneCreateRequest request) {

        Optional<MilestoneEntity> created = milestoneService.create(projectId, request);
        if (created.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, "Project not found: " + projectId);
            problem.setTitle(ErrorTitleConstants.PROJECT_NOT_FOUND);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
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
    public ResponseEntity<Object> updateMilestone(
            @PathVariable String projectId,
            @PathVariable String milestoneId,
            @Valid @RequestBody MilestoneUpdateRequest request) {

        Optional<MilestoneEntity> updated = milestoneService.update(milestoneId, request);
        if (updated.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, MILESTONE_NOT_FOUND_DETAIL + milestoneId);
            problem.setTitle(ErrorTitleConstants.MILESTONE_NOT_FOUND);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
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
    public ResponseEntity<Object> deleteMilestone(
            @PathVariable String projectId,
            @PathVariable String milestoneId) {

        if (!milestoneService.delete(milestoneId)) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, MILESTONE_NOT_FOUND_DETAIL + milestoneId);
            problem.setTitle(ErrorTitleConstants.MILESTONE_NOT_FOUND);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }
        return ResponseEntity.noContent().build();
    }

}
