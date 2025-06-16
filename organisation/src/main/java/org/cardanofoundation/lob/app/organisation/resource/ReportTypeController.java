package org.cardanofoundation.lob.app.organisation.resource;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.organisation.domain.request.ReportTypeFieldUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.AccountEventView;
import org.cardanofoundation.lob.app.organisation.domain.view.ReportTypeView;
import org.cardanofoundation.lob.app.organisation.service.ReportTypeService;

@RestController
@RequestMapping("/api/organisation/report-types")
@Tag(name = "Organisation", description = "Organisation API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.organisation.enabled", havingValue = "true", matchIfMissing = true)
public class ReportTypeController {

    private final ReportTypeService reportTypeService;

    @Operation(description = "Report Types", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = AccountEventView.class)))}
            ),
    })
    @GetMapping(value = "/{orgId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ReportTypeView>> getReferenceCodes(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(reportTypeService.getAllReportTypes(orgId));
    }

    @Operation(description = "Add mapping to Report Type field")
    @PostMapping(value = "/{orgId}/field-mappings", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> addMappingToReportTypeField(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @Valid @RequestBody ReportTypeFieldUpdate reportTypeFieldUpdate) {
        if (reportTypeService.addMappingToReportTypeField(orgId, reportTypeFieldUpdate).isLeft()) {
            Problem problem = reportTypeService.addMappingToReportTypeField(orgId, reportTypeFieldUpdate).getLeft();
            ResponseEntity.status(Objects.requireNonNull(problem.getStatus()).getStatusCode()).body(problem);
        }
        return ResponseEntity.ok().body(true);
    }

    @Operation(description = "Add mapping to Report Type field via CSV")
    @PostMapping(value = "/{orgId}/field-mappings", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> addMappingToReportTypeField(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @RequestParam(value = "file") MultipartFile file) {
        if (reportTypeService.addMappingToReportTypeFieldCsv(orgId, file).isLeft()) {
            Set<Problem> left = reportTypeService.addMappingToReportTypeFieldCsv(orgId, file).getLeft();
            return ResponseEntity.status(Objects.requireNonNull(400)).body(left);
        }
        return ResponseEntity.ok().body(true);
    }

}
