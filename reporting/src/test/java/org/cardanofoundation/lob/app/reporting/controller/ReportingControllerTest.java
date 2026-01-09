package org.cardanofoundation.lob.app.reporting.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.reporting.dto.CreateCsvReportRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportGenerateRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportListResponseDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.service.CsvReportService;
import org.cardanofoundation.lob.app.reporting.service.ReportingService;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class ReportingControllerTest {

    @Mock
    private ReportingService reportService;
    @Mock
    private CsvReportService csvReportService;
    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;
    @Mock
    private JpaSortFieldValidator jpaSortFieldValidator;

    @InjectMocks
    private ReportingController reportingController;

    private ReportDto reportDto;
    private ReportResponseDto reportResponseDto;
    private ReportGenerateRequest generateRequest;

    @BeforeEach
    void setUp() {
        reportDto = new ReportDto();
        reportDto.setName("Test Report");
        reportDto.setOrganisationId("org123");
        reportDto.setReportTemplateId("abc");
        reportDto.setFields(new ArrayList<>());

        reportResponseDto = new ReportResponseDto();
        reportResponseDto.setId("abc");
        reportResponseDto.setName("Test Report");
        reportResponseDto.setOrganisationId("org123");

        generateRequest = new ReportGenerateRequest();
        generateRequest.setReportTemplateId("abc");
        generateRequest.setOrganisationId("org123");
        generateRequest.setIntervalType("MONTHLY");
        generateRequest.setYear((short) 2024);
        generateRequest.setPeriod((short) 1);
    }

    @Test
    void create_Success() {
        // Given
        when(reportService.create(any(ReportDto.class)))
                .thenReturn(reportResponseDto);
        reportResponseDto.setError(Optional.empty());
        // When
        ResponseEntity<?> response = reportingController.create(reportDto);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(reportResponseDto, response.getBody());
        verify(reportService).create(any(ReportDto.class));
    }

    @Test
    void create_ServiceError() {
        // Given
        Problem problem = Problem.builder()
                .withTitle("Template Not Found")
                .withStatus(Status.NOT_FOUND)
                .build();
        ReportResponseDto responseDto = ReportResponseDto.builder().error(Optional.of(problem)).build();

        when(reportService.create(any(ReportDto.class)))
                .thenReturn(responseDto);

        // When
        ResponseEntity<?> response = reportingController.create(reportDto);

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(responseDto, response.getBody());
    }

    @Test
    void findById_Success() {
        // Given
        reportResponseDto.setOrganisationId("org123");
        when(reportService.findById("abc")).thenReturn(Optional.of(reportResponseDto));
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);

        // When
        ResponseEntity<?> response = reportingController.findById("abc", null);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(reportResponseDto, response.getBody());
    }

    @Test
    void findById_NotFound() {
        // Given
        when(reportService.findById("abc")).thenReturn(Optional.empty());

        // When
        ResponseEntity<?> response = reportingController.findById("abc", null);

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void findById_NoOrganisationAccess() {
        // Given
        reportResponseDto.setOrganisationId("org123");
        when(reportService.findById("abc")).thenReturn(Optional.of(reportResponseDto));
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        // When
        ResponseEntity<?> response = reportingController.findById("abc", null);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void findById_WithOrganisationIdParameter_Success() {
        // Given
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
        when(reportService.findByOrganisationIdAndId("org123", "abc"))
                .thenReturn(Optional.of(reportResponseDto));

        // When
        ResponseEntity<?> response = reportingController.findById("abc", "org123");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(reportResponseDto, response.getBody());
    }

    @Test
    void findAll_Success() {
        // Given
        ReportListResponseDto reportListResponseDto = new ReportListResponseDto();
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
        when(jpaSortFieldValidator.convertPageable(any(), any(), any())).thenReturn(Either.right(Pageable.unpaged()));
        when(reportService.findAll("org123", null, null, null, null, null, null, null, null, null, Pageable.unpaged())).thenReturn(reportListResponseDto);

        // When
        ResponseEntity<?> response = reportingController.findAll("org123", null, null, null, null, null, null, null, null, null, Pageable.unpaged());

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(reportListResponseDto, response.getBody());
    }

    @Test
    void findAll_NoOrganisationAccess() {
        // Given
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        // When
        ResponseEntity<?> response = reportingController.findAll("org123", null, null, null, null, null, null, null, null, null, Pageable.unpaged());


        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(reportService, never()).findByOrganisationId(any());
    }

    @Test
    void delete_Success() {
        // Given
        reportResponseDto.setOrganisationId("org123");
        when(reportService.findById("abc")).thenReturn(Optional.of(reportResponseDto));
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
        when(reportService.delete("abc")).thenReturn(Either.right(null));

        // When
        ResponseEntity<?> response = reportingController.delete("abc");

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(reportService).delete("abc");
    }

    @Test
    void delete_ReportNotFound() {
        // Given
        when(reportService.findById("abc")).thenReturn(Optional.empty());

        // When
        ResponseEntity<?> response = reportingController.delete("abc");

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(reportService, never()).delete(anyString());
    }

    @Test
    void delete_NoOrganisationAccess() {
        // Given
        reportResponseDto.setOrganisationId("org123");
        when(reportService.findById("abc")).thenReturn(Optional.of(reportResponseDto));
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        // When
        ResponseEntity<?> response = reportingController.delete("abc");

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(reportService, never()).delete(anyString());
    }

    @Test
    void delete_CannotDeletePublishedReport() {
        // Given
        Problem problem = Problem.builder()
                .withTitle("Report Already Published")
                .withStatus(Status.BAD_REQUEST)
                .build();

        reportResponseDto.setOrganisationId("org123");
        when(reportService.findById("abc")).thenReturn(Optional.of(reportResponseDto));
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
        when(reportService.delete("abc")).thenReturn(Either.left(problem));

        // When
        ResponseEntity<?> response = reportingController.delete("abc");

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(problem, response.getBody());
    }

    @Test
    void generate_Success() {
        // Given
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
        when(reportService.generate(any(ReportGenerateRequest.class)))
                .thenReturn(Either.right(reportResponseDto));

        // When
        ResponseEntity<?> response = reportingController.generate(generateRequest);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(reportResponseDto, response.getBody());
        verify(reportService).generate(any(ReportGenerateRequest.class));
    }

    @Test
    void generate_NoOrganisationAccess() {
        // Given
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        // When
        ResponseEntity<?> response = reportingController.generate(generateRequest);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(reportService, never()).generate(any());
    }

    @Test
    void generate_ServiceError() {
        // Given
        Problem problem = Problem.builder()
                .withTitle("Template Not Found")
                .withStatus(Status.NOT_FOUND)
                .build();

        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
        when(reportService.generate(any(ReportGenerateRequest.class)))
                .thenReturn(Either.left(problem));

        // When
        ResponseEntity<?> response = reportingController.generate(generateRequest);

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(problem, response.getBody());
    }

    @Test
    void templateCreateCsv_problem() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        Problem problem = Problem.builder()
                .withTitle("CSV Creation Failed")
                .withStatus(Status.INTERNAL_SERVER_ERROR)
                .build();
        ReportResponseDto reportResponseDto = ReportResponseDto.builder().error(Optional.of(problem)).build();

        when(csvReportService.createCsvReports(request)).thenReturn(Either.left(problem));

        ResponseEntity<List<ReportResponseDto>> response = reportingController.templateCreateCsv(request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(List.of(reportResponseDto), response.getBody());
    }

    @Test
    void templateCreateCsv_success() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        ReportResponseDto reportResponseDto = ReportResponseDto.builder().build();

        when(csvReportService.createCsvReports(request)).thenReturn(Either.right(List.of(reportResponseDto)));

        ResponseEntity<List<ReportResponseDto>> response = reportingController.templateCreateCsv(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(List.of(reportResponseDto), response.getBody());
    }

    @Test
    void reprocess_success() {
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
        when(reportService.reprocess("org123", "report123")).thenReturn(Either.right(reportResponseDto));

        ResponseEntity<?> response = reportingController.reprocess( "report123", "org123");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(reportResponseDto, response.getBody());
    }

    @Test
    void reprocess_OrgNotAccessible() {
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        ResponseEntity<?> response = reportingController.reprocess("report123", "org123");
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void reporcess_problem() {
        Problem problem = Problem.builder()
                .withTitle("Reprocess Failed")
                .withStatus(Status.INTERNAL_SERVER_ERROR)
                .build();
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
        when(reportService.reprocess("org123", "report123")).thenReturn(Either.left(problem));

        ResponseEntity<?> response = reportingController.reprocess("report123", "org123");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(problem, response.getBody());
    }
}
