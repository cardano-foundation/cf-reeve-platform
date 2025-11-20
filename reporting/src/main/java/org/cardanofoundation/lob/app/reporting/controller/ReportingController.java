package org.cardanofoundation.lob.app.reporting.controller;

import java.util.List;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.reporting.dto.ReportDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportGenerateRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportPublishRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.service.ReportingService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@RestController
@RequestMapping("/api/v1/reporting/reports")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Reports", description = "Manage reports based on templates with column data")
public class ReportingController {

    private final ReportingService reportService;
    private final KeycloakSecurityHelper keycloakSecurityHelper;

    @Operation(
        summary = "Create a new report",
        description = "Creates and saves a new report. If dataMode is GENERATED, fields are auto-calculated. If dataMode is USER, fields must be provided in the request.",
        responses = {
            @ApiResponse(
                responseCode = "201",
                description = "Report created successfully",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReportResponseDto.class)
                )
            ),
            @ApiResponse(responseCode = "400", description = "Invalid input data or missing required fields"),
            @ApiResponse(responseCode = "403", description = "User does not have access to this organisation"),
            @ApiResponse(responseCode = "404", description = "Report template not found")
        }
    )
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<?> create(
        @Valid @RequestBody @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Report data. For GENERATED dataMode, fields are auto-calculated. For USER dataMode, provide fields manually.",
            required = true
        ) ReportDto report
    ) {
        log.info("POST /api/reports - Creating report: {}", report.getName());

        // Check organisation access
        if (!keycloakSecurityHelper.canUserAccessOrg(report.getOrganisationId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body("User does not have access to this organisation");
        }

        Either<Problem, ReportResponseDto> result = reportService.create(report);

        if (result.isLeft()) {
            Problem problem = result.getLeft();
            return ResponseEntity
                .status(problem.getStatus().getStatusCode())
                .body(problem);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(result.get());
    }

    @Operation(
        summary = "Get report by ID",
        description = "Retrieves a report by its ID with all associated column values in hierarchical structure",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Report found",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReportResponseDto.class)
                )
            ),
            @ApiResponse(responseCode = "403", description = "User does not have access to this organisation"),
            @ApiResponse(responseCode = "404", description = "Report not found")
        }
    )
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> findById(
        @PathVariable @Parameter(description = "Report ID (hash-based)", example = "b1c2d3e4f5g6h7i8j9k0l1m2n3o4p5q6r7s8t9u0v1w2x3y4z5a6b7c8d9e0f1g2") String id,
        @RequestParam(value = "organisationId", required = false)
        @Parameter(
            description = "Organisation ID for ownership validation",
            example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"
        ) String organisationId
    ) {
        log.info("GET /api/reports/{} - organisationId: {}", id, organisationId);

        // If organisationId is provided, check access
        if (organisationId != null && !keycloakSecurityHelper.canUserAccessOrg(organisationId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body("User does not have access to this organisation");
        }

        if (organisationId != null) {
            return reportService.findByOrganisationIdAndId(organisationId, id)
                .map(report -> {
                    // Verify access to the report's organisation
                    if (!keycloakSecurityHelper.canUserAccessOrg(report.getOrganisationId())) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .<ReportResponseDto>body(null);
                    }
                    return ResponseEntity.ok(report);
                })
                .orElse(ResponseEntity.notFound().build());
        }

        return reportService.findById(id)
            .map(report -> {
                // Verify access to the report's organisation
                if (!keycloakSecurityHelper.canUserAccessOrg(report.getOrganisationId())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .<ReportResponseDto>body(null);
                }
                return ResponseEntity.ok(report);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
        summary = "List reports",
        description = "Retrieves all reports with optional filtering by organisation, template, year, and interval type",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "List of reports",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = ReportResponseDto.class))
                )
            ),
            @ApiResponse(responseCode = "403", description = "User does not have access to this organisation")
        }
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> findAll(
        @RequestParam(value = "organisationId", required = true)
        @Parameter(
            description = "Filter by organisation ID",
            example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"
        ) String organisationId,
        @RequestParam(value = "reportTemplateId", required = false)
        @Parameter(description = "Filter by report template ID (hash-based)", example = "a7b8c9d0e1f2g3h4i5j6k7l8m9n0o1p2q3r4s5t6u7v8w9x0y1z2") String reportTemplateId,
        @RequestParam(value = "year", required = false)
        @Parameter(description = "Filter by year", example = "2024") Short year,
        @RequestParam(value = "intervalType", required = false)
        @Parameter(description = "Filter by interval type", example = "MONTHLY", schema = @Schema(allowableValues = {"MONTHLY", "QUARTERLY", "YEARLY"})) String intervalType
    ) {
        log.info("GET /api/reports - organisationId: {}, templateId: {}, year: {}, intervalType: {}",
            organisationId, reportTemplateId, year, intervalType);

        // Check organisation access
        if (!keycloakSecurityHelper.canUserAccessOrg(organisationId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body("User does not have access to this organisation");
        }

        List<ReportResponseDto> reports;

        if (reportTemplateId != null) {
            reports = reportService.findByReportTemplateId(reportTemplateId);
        } else if (organisationId != null) {
            reports = reportService.findByOrganisationId(organisationId);
        } else {
            reports = reportService.findAll();
        }

        return ResponseEntity.ok(reports);
    }

    @Operation(
        summary = "Delete a report",
        description = "Deletes a report and all its associated column values",
        responses = {
            @ApiResponse(responseCode = "204", description = "Report deleted successfully"),
            @ApiResponse(responseCode = "400", description = "Report is already published and cannot be deleted"),
            @ApiResponse(responseCode = "403", description = "User does not have access to this organisation"),
            @ApiResponse(responseCode = "404", description = "Report not found")
        }
    )
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<?> delete(
        @PathVariable @Parameter(description = "Report ID (hash-based)", example = "b1c2d3e4f5g6h7i8j9k0l1m2n3o4p5q6r7s8t9u0v1w2x3y4z5a6b7c8d9e0f1g2") String id
    ) {
        log.info("DELETE /api/reports/{}", id);

        // First, get the report to check organisation access
        return reportService.findById(id)
            .map(report -> {
                // Check organisation access
                if (!keycloakSecurityHelper.canUserAccessOrg(report.getOrganisationId())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("User does not have access to this organisation");
                }

                // Proceed with deletion
                Either<Problem, Void> result = reportService.delete(id);

                if (result.isLeft()) {
                    Problem problem = result.getLeft();
                    return ResponseEntity.status(problem.getStatus().getStatusCode()).body(problem);
                }

                return ResponseEntity.noContent().build();
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
        summary = "Generate report preview from template",
        description = "Generates a report preview with automatically calculated field values. The report is NOT saved - use the create endpoint to save it.",
        responses = {
            @ApiResponse(
                responseCode = "201",
                description = "Report preview generated successfully (not saved)",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReportResponseDto.class)
                )
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
            @ApiResponse(responseCode = "403", description = "User does not have access to this organisation"),
            @ApiResponse(responseCode = "404", description = "Report template not found")
        }
    )
    @PostMapping(value = "/generate", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<?> generate(
        @Valid @RequestBody @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Request containing template ID, organisation ID, interval type, year and period. Fields will be auto-generated.",
            required = true
        ) ReportGenerateRequest request
    ) {
        log.info("POST /api/reports/generate - Template: {}, Org: {}, Year: {}, Interval: {}, Period: {}",
            request.getReportTemplateId(), request.getOrganisationId(), request.getYear(),
            request.getIntervalType(), request.getPeriod());

        // Check organisation access
        if (!keycloakSecurityHelper.canUserAccessOrg(request.getOrganisationId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body("User does not have access to this organisation");
        }

        Either<Problem, ReportResponseDto> result = reportService.generate(request);

        if (result.isLeft()) {
            Problem problem = result.getLeft();
            return ResponseEntity
                .status(problem.getStatus().getStatusCode())
                .body(problem);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(result.get());
    }

    @Operation(summary = "Publish a specific report",
            description = "Marking a report for publishing makes it read-only and available for stakeholders. This action cannot be undone.",
            responses = {
                    @ApiResponse(responseCode = "201",
                            description = "Report marked for publishing successfully",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = ReportResponseDto.class))),
                    @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
                    @ApiResponse(responseCode = "403",
                            description = "User does not have access to this organisation"),
                    @ApiResponse(responseCode = "404", description = "Report not found")})
    @PostMapping(value = "/publish", produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
    public ResponseEntity<?> publish(
            @Valid @RequestBody @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Request containing report id and organisation ID",
                    required = true) ReportPublishRequest request) {
        log.info(
                "POST /api/reports/publish - Org: {}, Report ID: {}",
                request.getOrganisationId(), request.getReportId());

        // Check organisation access
        if (!keycloakSecurityHelper.canUserAccessOrg(request.getOrganisationId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("User does not have access to this organisation");
        }

        Either<Problem, ReportResponseDto> result = reportService.publish(request);

        if (result.isLeft()) {
            Problem problem = result.getLeft();
            return ResponseEntity.status(problem.getStatus().getStatusCode()).body(problem);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(result.get());
    }
}
