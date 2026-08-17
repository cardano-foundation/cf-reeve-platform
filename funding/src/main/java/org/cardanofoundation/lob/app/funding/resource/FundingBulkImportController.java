package org.cardanofoundation.lob.app.funding.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.funding.domain.csv.FundingCsvFileType;
import org.cardanofoundation.lob.app.funding.domain.request.BulkImportRequest;
import org.cardanofoundation.lob.app.funding.domain.view.FundingBulkImportResult;
import org.cardanofoundation.lob.app.funding.service.FundingBulkImportService;
import org.cardanofoundation.lob.app.funding.service.FundingCsvTemplateService;

@Slf4j
@RestController
@RequestMapping("/api/v1/funding")
@Tag(name = "Funding", description = "Funding – Manage funding events, milestones and projects")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class FundingBulkImportController {

    private final FundingBulkImportService fundingBulkImportService;
    private final FundingCsvTemplateService fundingCsvTemplateService;

    @Operation(
            summary = "Bulk-import projects, milestones and/or events from CSV files",
            description = "Accepts up to two CSV files — Projects+Milestones (with arbitrary-depth sub-projects) " +
                    "and Events — whose type is auto-detected from their headers. Files are always processed in " +
                    "the order Projects+Milestones, then Events, regardless of upload order, so newly-created " +
                    "projects are visible to the Events file. Each file is processed independently: valid " +
                    "rows/groups are saved immediately, invalid ones are skipped and reported, and a failure in " +
                    "one file never blocks another. Set dryRun to validate and preview counts without persisting " +
                    "anything.",
            responses = {
                    @ApiResponse(responseCode = "200", content = {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = FundingBulkImportResult.class))}),
                    @ApiResponse(responseCode = "400", description = "No files uploaded"),
                    @ApiResponse(responseCode = "404", description = "Organisation not found")
            }
    )
    @PostMapping(value = "/bulk-import", produces = APPLICATION_JSON_VALUE, consumes = MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<FundingBulkImportResult> bulkImport(@ModelAttribute BulkImportRequest request) {
        return Responses.respond(fundingBulkImportService.importFiles(request), HttpStatus.OK);
    }

    @Operation(summary = "Download a blank CSV template (with one example row) for a bulk-import file type")
    @GetMapping(value = "/bulk-import/templates/{fileType}", produces = "text/csv")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<StreamingResponseBody> downloadTemplate(@PathVariable FundingCsvFileType fileType) {
        StreamingResponseBody responseBody = outputStream -> fundingCsvTemplateService.writeTemplate(fileType, outputStream);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"funding_%s_template.csv\"".formatted(fileType.name().toLowerCase()))
                .body(responseBody);
    }

}
