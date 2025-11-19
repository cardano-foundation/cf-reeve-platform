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

import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.service.ReportTemplateService;

@RestController
@RequestMapping("/api/v1/reporting/templates")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Reports", description = "Manage reports based on templates with column data")
public class ReportTemplateController {

    private final ReportTemplateService reportTemplateService;

    @Operation(summary = "Create a new report template",
            description = "Creates a new report template with hierarchical column structure for an organisation",
            responses = {
                    @ApiResponse(responseCode = "201",
                            description = "Report template created successfully",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(
                                            implementation = ReportTemplateResponseDto.class))),
                    @ApiResponse(responseCode = "400", description = "Invalid input data")})
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(
            @Valid @RequestBody(required = true) ReportTemplateDto template) {
        log.info("POST /api/report-templates - Creating template: {}", template.getName());
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(template);

        if (result.isLeft()) {
            Problem problem = result.getLeft();
            return ResponseEntity.status(problem.getStatus().getStatusCode()).body(problem);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(result.get());
    }

    @Operation(summary = "Get report template by ID",
            description = "Retrieves a report template by its ID with all associated columns in hierarchical structure",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Report template found",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(
                                            implementation = ReportTemplateResponseDto.class))),
                    @ApiResponse(responseCode = "404", description = "Report template not found")})
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReportTemplateResponseDto> findById(
            @PathVariable @Parameter(description = "Report template ID", example = "1") Long id) {
        log.info("GET /api/report-templates/{}", id);
        return reportTemplateService.findById(id).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "List report templates",
            description = "Retrieves all report templates, optionally filtered by organisation ID",
            responses = {
                    @ApiResponse(responseCode = "200", description = "List of report templates",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    array = @ArraySchema(schema = @Schema(
                                            implementation = ReportTemplateResponseDto.class))))})
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ReportTemplateResponseDto>> findAll(
            @RequestParam(value = "organisationId", required = true) @Parameter(
                    description = "Filter by organisation ID",
                    example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String organisationId) {
        log.info("GET /api/report-templates - organisationId: {}", organisationId);
        List<ReportTemplateResponseDto> templates =
                organisationId != null ? reportTemplateService.findByOrganisationId(organisationId)
                        : reportTemplateService.findAll();
        return ResponseEntity.ok(templates);
    }

    @Operation(summary = "Update a report template",
            description = "Updates an existing report template and all its columns (full replacement)",
            responses = {
                    @ApiResponse(responseCode = "200",
                            description = "Report template updated successfully",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(
                                            implementation = ReportTemplateResponseDto.class))),
                    @ApiResponse(responseCode = "404", description = "Report template not found"),
                    @ApiResponse(responseCode = "400", description = "Invalid input data")})
    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(
            @PathVariable @Parameter(description = "Report template ID", example = "1") Long id,
            @Valid @RequestBody @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Updated report template data",
                    required = true) ReportTemplateDto template) {
        log.info("PUT /api/report-templates/{}", id);
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.update(id, template);

        if (result.isLeft()) {
            Problem problem = result.getLeft();
            return ResponseEntity.status(problem.getStatus().getStatusCode()).body(problem);
        }

        return ResponseEntity.ok(result.get());
    }

    @Operation(summary = "Delete a report template",
            description = "Deletes a report template and all its associated columns",
            responses = {
                    @ApiResponse(responseCode = "204",
                            description = "Report template deleted successfully"),
                    @ApiResponse(responseCode = "404", description = "Report template not found")})
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable @Parameter(description = "Report template ID", example = "1") Long id) {
        log.info("DELETE /api/report-templates/{}", id);
        reportTemplateService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
