package org.cardanofoundation.lob.app.reporting.controller;


import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.reporting.dto.CreateCsvReportRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportGenerateRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportListResponseDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportPublishRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.service.CsvReportService;
import org.cardanofoundation.lob.app.reporting.service.ReportingService;
import org.cardanofoundation.lob.app.reporting.util.Constants;
import org.cardanofoundation.lob.app.reporting.util.PageableFieldMappings;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@RestController
@RequestMapping("/api/v1/reporting/reports")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Reports", description = "Manage reports based on templates with column data")
@ConditionalOnProperty(value = "lob.reporting_v2.enabled", havingValue = "true", matchIfMissing = true)
public class ReportingController {

    private final ReportingService reportService;
    private final CsvReportService csvReportService;
    private final KeycloakSecurityHelper keycloakSecurityHelper;
    private final JpaSortFieldValidator jpaSortFieldValidator;

    @Operation(
        summary = "Create a new report",
        description = "Creates and saves a new report. If dataMode is GENERATED, fields are auto-calculated. If dataMode is USER, fields must be provided in the request.",
        responses = {
            @ApiResponse(
                responseCode = "201",
                description = "Report created successfully",
                content = @Content(
                    mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReportResponseDto.class)
                )
            ),
            @ApiResponse(responseCode = "400", description = "Invalid input data or missing required fields"),
            @ApiResponse(responseCode = "403", description = Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION),
            @ApiResponse(responseCode = "404", description = "Report template not found")
        }
    )
    @PostMapping(produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<ReportResponseDto> create(
        @Valid @RequestBody @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Report data. For GENERATED dataMode, fields are auto-calculated. For USER dataMode, provide fields manually.",
            required = true
        ) ReportDto report
    ) {
        log.debug("POST /api/reports - Creating report: {}", report.getName());

        ReportResponseDto result = reportService.create(report);

        if (result.getError().isPresent()) {
            Problem problem = result.getError().get();
            return ResponseEntity
                .status(problem.getStatus().getStatusCode())
                .body(result);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @Tag(name = "Reporting", description = "Create Report from CSV")
    @PostMapping(produces = APPLICATION_JSON_VALUE, consumes = MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<List<ReportResponseDto>> templateCreateCsv(
            @Valid @ModelAttribute CreateCsvReportRequest csvReportRequest) {
        return csvReportService.createCsvReports(csvReportRequest)
                .fold(
                        error -> ResponseEntity.status(error.getStatus().getStatusCode()).body(List.of(ReportResponseDto.builder().error(Optional.of(error)).build())),
                        templates -> ResponseEntity.status(HttpStatus.CREATED).body(templates)
                );
    }

    @Operation(
        summary = "Get report by ID",
        description = "Retrieves a report by its ID with all associated column values in hierarchical structure",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Report found",
                content = @Content(
                    mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReportResponseDto.class)
                )
            ),
            @ApiResponse(responseCode = "403", description = Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION),
            @ApiResponse(responseCode = "404", description = "Report not found")
        }
    )
    @GetMapping(value = "/{id}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> findById(
        @PathVariable @Parameter(description = "Report ID (hash-based)", example = "b1c2d3e4f5g6h7i8j9k0l1m2n3o4p5q6r7s8t9u0v1w2x3y4z5a6b7c8d9e0f1g2") String id,
        @RequestParam(value = "organisationId", required = false)
        @Parameter(
            description = "Organisation ID for ownership validation",
            example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"
        ) String organisationId
    ) {
        log.debug("GET /api/reports/{} - organisationId: {}", id, organisationId);

        // If organisationId is provided, check access
        if (organisationId != null && !keycloakSecurityHelper.canUserAccessOrg(organisationId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION);
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
                    mediaType = APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = ReportResponseDto.class))
                )
            ),
            @ApiResponse(responseCode = "403", description = Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION)
        }
    )
    @GetMapping(produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> findAll(
            @RequestParam(value = "organisationId", required = true)
        @Parameter(
            description = "Filter by organisation ID",
            example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"
        ) String organisationId,
            @RequestParam(value = "year", required = false) List<Short> years,
            @RequestParam(value = "intervalType", required = false) List<IntervalType> intervalTypes,
            @RequestParam(value = "period", required = false) List<Short> periods,
            @RequestParam(value = "ledgerStatus", required = false) LedgerDispatchStatus ledgerStatus,
            @RequestParam(value = "reportType", required = false) List<ReportTemplateType> reportTypes,
            @RequestParam(value = "templateId", required = false) List<String> reportTemplateIds,
            @RequestParam(value = "txHash", required = false) String txHash,
            @RequestParam(value = "isReadyToPublish", required = false) Boolean isReadyToPublish,
            @RequestParam(value = "ledgerDispatchApproved", required = false) Boolean ledgerDispatchApproved,
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable
            ) {
        log.debug("GET /api/reports - organisationId: {}, templateIds: {}, years: {}, intervalTypes: {}",
            organisationId, reportTemplateIds, years, intervalTypes);

        // Check organisation access
        if (!keycloakSecurityHelper.canUserAccessOrg(organisationId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION);
        }
        Either<Problem, Pageable> pageableE = jpaSortFieldValidator.convertPageable(pageable, PageableFieldMappings.REPORT_MAPPINGS, ReportEntity.class);
        if (pageableE.isLeft()) {
            Problem problem = pageableE.getLeft();
            return ResponseEntity
                .status(Objects.requireNonNull(problem.getStatus()).getStatusCode())
                .body(problem);
        }
        ReportListResponseDto reportListDto = reportService.findAll(organisationId, years, intervalTypes, periods, ledgerStatus,
                reportTypes, reportTemplateIds, txHash, isReadyToPublish, ledgerDispatchApproved, pageableE.get());

        return ResponseEntity.ok(reportListDto);
    }

    @Operation(
        summary = "Delete a report",
        description = "Deletes a report and all its associated column values",
        responses = {
            @ApiResponse(responseCode = "204", description = "Report deleted successfully"),
            @ApiResponse(responseCode = "400", description = "Report is already published and cannot be deleted"),
            @ApiResponse(responseCode = "403", description = Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION),
            @ApiResponse(responseCode = "404", description = "Report not found")
        }
    )
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<?> delete(
        @PathVariable @Parameter(description = "Report ID (hash-based)", example = "b1c2d3e4f5g6h7i8j9k0l1m2n3o4p5q6r7s8t9u0v1w2x3y4z5a6b7c8d9e0f1g2") String id
    ) {
        log.debug("DELETE /api/reports/{}", id);

        // First, get the report to check organisation access
        return reportService.findById(id)
            .map(report -> {
                // Check organisation access
                if (!keycloakSecurityHelper.canUserAccessOrg(report.getOrganisationId())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION);
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
                    mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReportResponseDto.class)
                )
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
            @ApiResponse(responseCode = "403", description = Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION),
            @ApiResponse(responseCode = "404", description = "Report template not found")
        }
    )
    @PostMapping(value = "/generate", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<?> generate(
        @Valid @RequestBody @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Request containing template ID, organisation ID, interval type, year and period. Fields will be auto-generated.",
            required = true
        ) ReportGenerateRequest request
    ) {
        log.debug("POST /api/reports/generate - Template: {}, Org: {}, Year: {}, Interval: {}, Period: {}",
            request.getReportTemplateId(), request.getOrganisationId(), request.getYear(),
            request.getIntervalType(), request.getPeriod());

        // Check organisation access
        if (!keycloakSecurityHelper.canUserAccessOrg(request.getOrganisationId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION);
        }

        Either<Problem, ReportResponseDto> result = reportService.generate(request);

        if (result.isLeft()) {
            Problem problem = result.getLeft();
            return ResponseEntity
                .status(problem.getStatus().getStatusCode())
                .body(problem);
        }

        return ResponseEntity.status(HttpStatus.OK).body(result.get());
    }

    @Operation(summary = "Publish a specific report",
            description = "Marking a report for publishing makes it read-only and available for stakeholders. This action cannot be undone.",
            responses = {
                    @ApiResponse(responseCode = "201",
                            description = "Report marked for publishing successfully",
                            content = @Content(mediaType = APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = ReportResponseDto.class))),
                    @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
                    @ApiResponse(responseCode = "403",
                            description = Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION),
                    @ApiResponse(responseCode = "404", description = "Report not found")})
    @PostMapping(value = "/publish", produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
    public ResponseEntity<?> publish(
            @Valid @RequestBody @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Request containing report id and organisation ID",
                    required = true) ReportPublishRequest request) {
        log.debug(
                "POST /api/reports/publish - Org: {}, Report ID: {}",
                request.getOrganisationId(), request.getReportId());

        Either<Problem, ReportResponseDto> result = reportService.publish(request);

        if (result.isLeft()) {
            Problem problem = result.getLeft();
            return ResponseEntity.status(problem.getStatus().getStatusCode()).body(problem);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(result.get());
    }

    @Operation(
        summary = "Reprocess a report",
        description = "Re-evaluates validation rules for a report and updates the readyToPublish state and failedValidationRules. Can only be used on unpublished reports.",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Report reprocessed successfully",
                content = @Content(
                    mediaType = APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReportResponseDto.class)
                )
            ),
            @ApiResponse(responseCode = "400", description = "Report is already published and cannot be reprocessed"),
            @ApiResponse(responseCode = "403", description = Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION),
            @ApiResponse(responseCode = "404", description = "Report not found")
        }
    )
    @PostMapping(value = "/{id}/reprocess", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<?> reprocess(
        @PathVariable @Parameter(description = "Report ID (hash-based)", example = "b1c2d3e4f5g6h7i8j9k0l1m2n3o4p5q6r7s8t9u0v1w2x3y4z5a6b7c8d9e0f1g2") String id,
        @RequestParam(value = "organisationId", required = true)
        @Parameter(
            description = "Organisation ID for ownership validation",
            example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"
        ) String organisationId
    ) {
        log.debug("POST /api/reports/{}/reprocess - organisationId: {}", id, organisationId);

        // Check organisation access
        if (!keycloakSecurityHelper.canUserAccessOrg(organisationId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION);
        }

        Either<Problem, ReportResponseDto> result = reportService.reprocess(organisationId, id);

        if (result.isLeft()) {
            Problem problem = result.getLeft();
            return ResponseEntity.status(problem.getStatus().getStatusCode()).body(problem);
        }

        return ResponseEntity.ok(result.get());
    }
}
