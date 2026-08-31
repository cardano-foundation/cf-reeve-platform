package org.cardanofoundation.lob.app.funding.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.util.List;

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
import org.cardanofoundation.lob.app.funding.service.FundingCsvTemplateService;

@ExtendWith(MockitoExtension.class)
class FundingBulkImportControllerTest {

    @Mock
    private FundingBulkImportService fundingBulkImportService;
    @Mock
    private FundingCsvTemplateService fundingCsvTemplateService;

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

}
