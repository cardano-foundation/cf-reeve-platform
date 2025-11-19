package org.cardanofoundation.lob.app.reporting.controller;

import java.util.List;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.service.ReportingService;

@RestController
@RequestMapping("/api/v1/reporting/reports")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Reports", description = "Manage reports based on templates with column data")
public class ReportingController {

    private final ReportingService reportService;

    @Operation(
        summary = "Create a new report",
        description = "Creates a new report instance based on a template with column values",
        responses = {
            @ApiResponse(
                responseCode = "201",
                description = "Report created successfully",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReportResponseDto.class)
                )
            ),
            @ApiResponse(responseCode = "400", description = "Invalid input data"),
            @ApiResponse(responseCode = "404", description = "Report template not found")
        }
    )
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(
        @Valid @RequestBody @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Report data including template ID and column values",
            required = true
        ) ReportDto report
    ) {
        log.info("POST /api/reports - Creating report: {}", report.getName());
        Either<Problem, ReportResponseDto> result = reportService.create(report, true);

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
            @ApiResponse(responseCode = "404", description = "Report not found")
        }
    )
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReportResponseDto> findById(
        @PathVariable @Parameter(description = "Report ID", example = "1") Long id,
        @RequestParam(value = "organisationId", required = false)
        @Parameter(
            description = "Organisation ID for ownership validation",
            example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"
        ) String organisationId
    ) {
        log.info("GET /api/reports/{} - organisationId: {}", id, organisationId);
        if (organisationId != null) {
            return reportService.findByOrganisationIdAndId(organisationId, id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
        }
        return reportService.findById(id)
            .map(ResponseEntity::ok)
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
            )
        }
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ReportResponseDto>> findAll(
        @RequestParam(value = "organisationId", required = true)
        @Parameter(
            description = "Filter by organisation ID",
            example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"
        ) String organisationId,
        @RequestParam(value = "reportTemplateId", required = false)
        @Parameter(description = "Filter by report template ID", example = "1") Long reportTemplateId,
        @RequestParam(value = "year", required = false)
        @Parameter(description = "Filter by year", example = "2024") Short year,
        @RequestParam(value = "intervalType", required = false)
        @Parameter(description = "Filter by interval type", example = "MONTHLY", schema = @Schema(allowableValues = {"MONTHLY", "QUARTERLY", "YEARLY"})) String intervalType
    ) {
        log.info("GET /api/reports - organisationId: {}, templateId: {}, year: {}, intervalType: {}",
            organisationId, reportTemplateId, year, intervalType);

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
        summary = "Update a report",
        description = "Updates an existing report and all its column values (full replacement)",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Report updated successfully",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReportResponseDto.class)
                )
            ),
            @ApiResponse(responseCode = "404", description = "Report not found"),
            @ApiResponse(responseCode = "400", description = "Invalid input data")
        }
    )
    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(
        @PathVariable @Parameter(description = "Report ID", example = "1") Long id,
        @Valid @RequestBody @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Updated report data",
            required = true
        ) ReportDto report
    ) {
        log.info("PUT /api/reports/{}", id);
        Either<Problem, ReportResponseDto> result = reportService.update(id, report);

        if (result.isLeft()) {
            Problem problem = result.getLeft();
            return ResponseEntity
                .status(problem.getStatus().getStatusCode())
                .body(problem);
        }

        return ResponseEntity.ok(result.get());
    }

    @Operation(
        summary = "Delete a report",
        description = "Deletes a report and all its associated column values",
        responses = {
            @ApiResponse(responseCode = "204", description = "Report deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Report not found")
        }
    )
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
        @PathVariable @Parameter(description = "Report ID", example = "1") Long id
    ) {
        log.info("DELETE /api/reports/{}", id);
        reportService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "Generate report from template",
        description = "Generates a new report based on a template with empty column values to be filled",
        responses = {
            @ApiResponse(
                responseCode = "201",
                description = "Report generated successfully",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReportResponseDto.class)
                )
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
            @ApiResponse(responseCode = "404", description = "Report template not found")
        }
    )
    @PostMapping(value = "/generate", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generate(
        @Valid @RequestBody @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Request containing template ID, organisation ID, interval type, year and period",
            required = true
        ) ReportGenerateRequest request
    ) {
        log.info("POST /api/reports/generate - Template: {}, Org: {}, Year: {}, Interval: {}, Period: {}",
            request.getReportTemplateId(), request.getOrganisationId(), request.getYear(),
            request.getIntervalType(), request.getPeriod());

        Either<Problem, ReportResponseDto> result = reportService.generate(request);

        if (result.isLeft()) {
            Problem problem = result.getLeft();
            return ResponseEntity
                .status(problem.getStatus().getStatusCode())
                .body(problem);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(result.get());
    }


}
