package org.cardanofoundation.lob.app.reporting.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

import org.cardanofoundation.lob.app.reporting.dto.CreateCsvTemplateRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.service.CsvReportTemplateService;
import org.cardanofoundation.lob.app.reporting.service.ReportTemplateService;
import org.cardanofoundation.lob.app.reporting.util.Constants;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@RestController
@RequestMapping("/api/v1/reporting/templates")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Reports", description = "Manage reports based on templates with column data")
@ConditionalOnProperty(value = "lob.reporting_v2.enabled", havingValue = "true", matchIfMissing = true)
public class ReportTemplateController {

    private final ReportTemplateService reportTemplateService;
    private final CsvReportTemplateService csvReportTemplateService;
    private final KeycloakSecurityHelper keycloakSecurityHelper;
    private final JpaSortFieldValidator jpaSortFieldValidator;

    @GetMapping(value = "/types", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<List<String>> getReportTypes() {
        return ResponseEntity.ok(Arrays.stream(ReportTemplateType.values()).map(ReportTemplateType::name).toList());
    }

    @Operation(summary = "Create a new report template",
            description = "Creates a new report template with hierarchical column structure for an organisation. Returns 409 if template already exists.",
            responses = {
                    @ApiResponse(responseCode = "201",
                            description = "Report template created successfully",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(
                                            implementation = ReportTemplateResponseDto.class))),
                    @ApiResponse(responseCode = "400", description = "Invalid input data"),
                    @ApiResponse(responseCode = "403", description = Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION),
                    @ApiResponse(responseCode = "409", description = "Template already exists - use PUT to update")})
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<ReportTemplateResponseDto> create(
            @RequestBody(required = true) ReportTemplateDto template) {
        log.debug("POST /api/report-templates - Creating template: {}", template.getName());

        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(template);

        if (result.isLeft()) {
            Problem problem = result.getLeft();
            return ResponseEntity.status(Objects.requireNonNull(problem.getStatus()).getStatusCode()).body(ReportTemplateResponseDto.builder().error(Optional.of(problem)).build());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(result.get());
    }

    @Tag(name = "Reporting", description = "Create Report Template from CSV")
    @PostMapping(produces = APPLICATION_JSON_VALUE, consumes = MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<List<ReportTemplateResponseDto>> templateCreateCsv(
            @ModelAttribute CreateCsvTemplateRequest csvTemplateRequest) {

        return csvReportTemplateService.createCsvTemplates(csvTemplateRequest)
                .fold(
                        error -> ResponseEntity.status(error.getStatus().getStatusCode()).body(List.of(ReportTemplateResponseDto.builder().error(Optional.of(error)).build())),
                        templates -> ResponseEntity.status(HttpStatus.CREATED).body(templates)
                );
    }

    @Operation(summary = "Update an existing report template",
            description = "Updates an existing report template. If the template has associated published reports, a new version will be created. Otherwise, the existing template will be updated in place.",
            responses = {
                    @ApiResponse(responseCode = "200",
                            description = "Report template updated successfully",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(
                                            implementation = ReportTemplateResponseDto.class))),
                    @ApiResponse(responseCode = "400", description = "Invalid input data"),
                    @ApiResponse(responseCode = "403", description = Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION),
                    @ApiResponse(responseCode = "404", description = "Template not found - use POST to create")})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Report template data to update",
            required = true,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReportTemplateDto.class)))
    @org.springframework.web.bind.annotation.PutMapping(produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<?> update(
            @RequestBody(required = true) ReportTemplateDto template) {
        log.debug("PUT /api/report-templates - Updating template: {}", template.getName());


        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.update(template);

        if (result.isLeft()) {
            Problem problem = result.getLeft();
            return ResponseEntity.status(problem.getStatus().getStatusCode()).body(problem);
        }

        return ResponseEntity.ok(result.get());
    }

    @Operation(summary = "Get report template by ID",
            description = "Retrieves a report template by its ID with all associated columns in hierarchical structure",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Report template found",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(
                                            implementation = ReportTemplateResponseDto.class))),
                    @ApiResponse(responseCode = "403", description = Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION),
                    @ApiResponse(responseCode = "404", description = "Report template not found")})
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> findById(
            @PathVariable @Parameter(description = "Report template ID (hash-based)", example = "a7b8c9d0e1f2g3h4i5j6k7l8m9n0o1p2q3r4s5t6u7v8w9x0y1z2") String id) {
        log.debug("GET /api/report-templates/{}", id);
        return reportTemplateService.findById(id)
            .map(template -> {
                // Check organisation access
                if (!keycloakSecurityHelper.canUserAccessOrg(template.getOrganisationId())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION);
                }
                return ResponseEntity.ok(template);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "List report templates",
            description = "Retrieves all report templates, optionally filtered by organisation ID",
            responses = {
                    @ApiResponse(responseCode = "200", description = "List of report templates",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    array = @ArraySchema(schema = @Schema(
                                            implementation = ReportTemplateResponseDto.class)))),
                    @ApiResponse(responseCode = "403", description = Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION)})
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> findAll(
            @RequestParam(value = "organisationId", required = true) @Parameter(
                    description = "Filter by organisation ID",
                    example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String organisationId,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "reportTemplateType", required = false) List<ReportTemplateType> reportTemplateTypes,
            @RequestParam(value = "active", required = false) Boolean active,
            @RequestParam(value = "dataMode", required = false) List<DataMode> dataMode,
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        log.debug("GET /api/report-templates - organisationId: {}", organisationId);

        // Check organisation access
        if (!keycloakSecurityHelper.canUserAccessOrg(organisationId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION);
        }
        Either<Problem, Pageable> pageableE = jpaSortFieldValidator.convertPageable(pageable, Map.of(), ReportTemplateEntity.class);
        if (pageableE.isLeft()) {
            Problem problem = pageableE.getLeft();
            return ResponseEntity.status(Objects.requireNonNull(problem.getStatus()).getStatusCode()).body(problem);
        }
        List<ReportTemplateResponseDto> templates = reportTemplateService.findAll(organisationId, name, description, reportTemplateTypes, active, dataMode, pageableE.get());
        return ResponseEntity.ok(templates);
    }

    @Operation(summary = "Delete a report template",
            description = "Deletes a report template and all its associated columns",
            responses = {
                    @ApiResponse(responseCode = "204",
                            description = "Report template deleted successfully"),
                    @ApiResponse(responseCode = "400",
                            description = "Template has associated reports and cannot be deleted"),
                    @ApiResponse(responseCode = "403", description = Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION),
                    @ApiResponse(responseCode = "404", description = "Report template not found")})
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<?> delete(
            @PathVariable @Parameter(description = "Report template ID (hash-based)", example = "a7b8c9d0e1f2g3h4i5j6k7l8m9n0o1p2q3r4s5t6u7v8w9x0y1z2") String id) {
        log.debug("DELETE /api/report-templates/{}", id);

        // First, get the template to check organisation access
        return reportTemplateService.findById(id)
            .map(template -> {
                // Check organisation access
                if (!keycloakSecurityHelper.canUserAccessOrg(template.getOrganisationId())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Constants.USER_DOES_NOT_HAVE_ACCESS_TO_THIS_ORGANISATION);
                }

                // Proceed with deletion
                Either<Problem, Void> result = reportTemplateService.delete(id);

                if (result.isLeft()) {
                    Problem problem = result.getLeft();
                    return ResponseEntity.status(problem.getStatus().getStatusCode()).body(problem);
                }

                return ResponseEntity.noContent().build();
            })
            .orElse(ResponseEntity.notFound().build());
    }

}
