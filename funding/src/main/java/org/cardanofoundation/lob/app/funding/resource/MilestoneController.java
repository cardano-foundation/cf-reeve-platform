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
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.service.MilestoneService;

@Slf4j
@RestController
@RequestMapping("/api/v1/funding")
@Tag(name = "Funding", description = "Funding – Manage funding events, milestones and projects")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class MilestoneController {

    private final MilestoneService milestoneService;

    @Operation(description = "List milestones for a project", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PagedResponse.class))})
    })
    @GetMapping(value = "/projects/{projectId}/milestones", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<PagedResponse<MilestoneView>> listMilestones(
            @PathVariable String projectId,
            @RequestParam String organisationId,
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        return Responses.respond(milestoneService.listMilestones(projectId, organisationId, pageable), HttpStatus.OK);
    }

    @Operation(description = "Get a single milestone", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = MilestoneView.class))})
    })
    @GetMapping(value = "/projects/{projectId}/milestones/{milestoneId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<MilestoneView> getMilestone(
            @PathVariable String projectId,
            @PathVariable String milestoneId,
            @RequestParam String organisationId) {
        return Responses.respond(milestoneService.getMilestone(projectId, milestoneId, organisationId), HttpStatus.OK);
    }

    @Operation(description = "Create a milestone for a project", responses = {
            @ApiResponse(responseCode = "201", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = MilestoneView.class))})
    })
    @PostMapping(value = "/projects/{projectId}/milestones", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<MilestoneView> createMilestone(
            @PathVariable String projectId,
            @RequestParam String organisationId,
            @Valid @RequestBody MilestoneCreateRequest request) {
        return Responses.respond(milestoneService.createMilestone(projectId, organisationId, request), HttpStatus.CREATED);
    }

    @Operation(description = "Update a milestone", responses = {
            @ApiResponse(content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = MilestoneView.class))})
    })
    @PutMapping(value = "/projects/{projectId}/milestones/{milestoneId}", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<MilestoneView> updateMilestone(
            @PathVariable String projectId,
            @PathVariable String milestoneId,
            @RequestParam String organisationId,
            @Valid @RequestBody MilestoneUpdateRequest request) {
        return Responses.respond(milestoneService.updateMilestone(projectId, milestoneId, organisationId, request), HttpStatus.OK);
    }

    @Operation(description = "Delete a milestone", responses = {
            @ApiResponse(responseCode = "204"),
            @ApiResponse(responseCode = "404", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))})
    })
    @DeleteMapping(value = "/projects/{projectId}/milestones/{milestoneId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ProblemDetail> deleteMilestone(
            @PathVariable String projectId,
            @PathVariable String milestoneId,
            @RequestParam String organisationId) {
        return Responses.respondDelete(milestoneService.deleteMilestone(projectId, milestoneId, organisationId));
    }

}
