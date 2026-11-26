package org.cardanofoundation.lob.app.reporting.controller;

import java.util.List;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.reporting.dto.ReportStandardDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportStandardResponseDto;
import org.cardanofoundation.lob.app.reporting.service.ReportStandardService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;
import org.cardanofoundation.lob.app.support.security.OrgAccessDenied;

@RestController
@RequestMapping("/api/v1/organisations")
@Tag(name = "Report Standards", description = "Manage the report standards (IFRS, US GAAP, ...) an organisation can declare on report templates")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class ReportStandardController {

    private final ReportStandardService reportStandardService;
    private final KeycloakSecurityHelper keycloakSecurityHelper;

    @Operation(description = "Get all approved report standards for an organisation", responses = {
            @ApiResponse(content =
                    { @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ReportStandardResponseDto.class))) }
            ),
    })
    @GetMapping(value = "/{orgId}/report-standards", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permissions.can(#orgId, 'report_standards', 'view')")
    public ResponseEntity<List<ReportStandardResponseDto>> getAllReportStandards(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return OrgAccessDenied.response();
        }
        return ResponseEntity.ok(reportStandardService.getAllReportStandards(orgId));
    }

    @Operation(description = "Report Standard Insert", responses = {
            @ApiResponse(content =
                    { @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ReportStandardResponseDto.class))) }
            ),
    })
    @PostMapping(value = "/{orgId}/report-standards", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permissions.can(#orgId, 'report_standards', 'create')")
    public ResponseEntity<ReportStandardResponseDto> insertReportStandard(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @Valid @RequestBody ReportStandardDto reportStandardUpdate) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return OrgAccessDenied.response();
        }
        ReportStandardResponseDto view = reportStandardService.insertReportStandard(orgId, reportStandardUpdate, false);
        return view.getError().map(error -> ResponseEntity.status(error.getStatus()).body(view))
                .orElse(ResponseEntity.ok(view));
    }

    @Operation(description = "Report Standard Update", responses = {
            @ApiResponse(content =
                    { @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ReportStandardResponseDto.class))) }
            ),
    })
    @PutMapping(value = "/{orgId}/report-standards", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permissions.can(#orgId, 'report_standards', 'edit')")
    public ResponseEntity<ReportStandardResponseDto> updateReportStandard(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @Valid @RequestBody ReportStandardDto reportStandardUpdate) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return OrgAccessDenied.response();
        }
        ReportStandardResponseDto view = reportStandardService.updateReportStandard(orgId, reportStandardUpdate);
        return view.getError().map(error -> ResponseEntity.status(error.getStatus()).body(view))
                .orElse(ResponseEntity.ok(view));
    }

    @Operation(description = "Report Standard Upload", responses = {
            @ApiResponse(content =
                    { @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ReportStandardResponseDto.class))) }
            ),
    })
    @PostMapping(value = "/{orgId}/report-standards", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@permissions.can(#orgId, 'report_standards', 'create')")
    public ResponseEntity<Object> insertReportStandardsCsv(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @RequestParam(value = "file") MultipartFile file) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return OrgAccessDenied.response();
        }
        return reportStandardService.insertViaCsv(orgId, file).fold(
                problem -> ResponseEntity.status(HttpStatus.BAD_REQUEST.value()).body(problem),
                ResponseEntity::ok
        );
    }
}
