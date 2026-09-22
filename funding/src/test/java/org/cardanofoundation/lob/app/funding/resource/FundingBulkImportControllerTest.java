package org.cardanofoundation.lob.app.funding.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.funding.domain.csv.FundingCsvFileType;
import org.cardanofoundation.lob.app.funding.domain.request.BulkImportRequest;
import org.cardanofoundation.lob.app.funding.domain.view.FundingBulkImportResult;
import org.cardanofoundation.lob.app.funding.service.FundingBulkImportService;
import org.cardanofoundation.lob.app.funding.service.FundingCsvExportService;
import org.cardanofoundation.lob.app.funding.service.FundingCsvTemplateService;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;

@ExtendWith(MockitoExtension.class)
class FundingBulkImportControllerTest {

    private static final String ORG_ID = "org1";
    private static final Pageable PAGEABLE = PageRequest.of(0, 10);

    @Mock
    private FundingBulkImportService fundingBulkImportService;
    @Mock
    private FundingCsvTemplateService fundingCsvTemplateService;
    @Mock
    private FundingCsvExportService fundingCsvExportService;

    @InjectMocks
    private FundingBulkImportController controller;

    @Test
    void bulkImport_returns200_onSuccess() {
        FundingBulkImportResult successResult = FundingBulkImportResult.builder().projectsCreated(1).build();
        BulkImportRequest request = BulkImportRequest.builder().organisationId("org1").files(List.of()).build();
        when(fundingBulkImportService.importFiles(request)).thenReturn(successResult);

        ResponseEntity<FundingBulkImportResult> response = controller.bulkImport(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(successResult);
    }

    @Test
    void bulkImport_propagatesErrorStatus() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "No files uploaded");
        problem.setTitle("NO_FILES_UPLOADED");
        FundingBulkImportResult errorResult = FundingBulkImportResult.error(problem);
        BulkImportRequest request = BulkImportRequest.builder().organisationId("org1").files(List.of()).build();
        when(fundingBulkImportService.importFiles(request)).thenReturn(errorResult);

        ResponseEntity<FundingBulkImportResult> response = controller.bulkImport(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isSameAs(errorResult);
    }

    @Test
    void downloadTemplate_streamsFromTemplateService() throws Exception {
        ResponseEntity<StreamingResponseBody> response = controller.downloadTemplate(FundingCsvFileType.PROJECTS_MILESTONES);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("attachment; filename=\"funding_projects_milestones_template.csv\"");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);

        verify(fundingCsvTemplateService).writeTemplate(eq(FundingCsvFileType.PROJECTS_MILESTONES), any());
    }

    @Test
    void downloadTemplate_usesFileTypeSpecificFilename() {
        ResponseEntity<StreamingResponseBody> response = controller.downloadTemplate(FundingCsvFileType.EVENTS);

        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("attachment; filename=\"funding_events_template.csv\"");
    }

    @Test
    void exportProjectsMilestones_streamsFromExportService_whenValidationPasses() throws Exception {
        when(fundingCsvExportService.validateExport(ORG_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.exportProjectsMilestones(ORG_ID, null, PAGEABLE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("attachment; filename=\"funding_projects_milestones_export.csv\"");
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("text/csv");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ((StreamingResponseBody) response.getBody()).writeTo(out);

        verify(fundingCsvExportService).writeProjectsMilestonesExport(eq(ORG_ID), isNull(), eq(PAGEABLE), any());
    }

    @Test
    void exportProjectsMilestones_passesProIdsThrough_whenSupplied() throws Exception {
        List<String> proIds = List.of("PRJ-1", "PRJ-2");
        when(fundingCsvExportService.validateExport(ORG_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.exportProjectsMilestones(ORG_ID, proIds, PAGEABLE);
        ((StreamingResponseBody) response.getBody()).writeTo(new ByteArrayOutputStream());

        verify(fundingCsvExportService).writeProjectsMilestonesExport(eq(ORG_ID), eq(proIds), eq(PAGEABLE), any());
    }

    @Test
    void exportProjectsMilestones_returnsErrorStatus_withoutStreaming_whenValidationFails() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "User does not have access to this organisation");
        problem.setTitle(ErrorTitleConstants.UNAUTHORIZED);
        when(fundingCsvExportService.validateExport(ORG_ID)).thenReturn(Optional.of(problem));

        ResponseEntity<?> response = controller.exportProjectsMilestones(ORG_ID, null, PAGEABLE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isSameAs(problem);
        verify(fundingCsvExportService, never()).writeProjectsMilestonesExport(any(), any(), any(), any());
    }

}
