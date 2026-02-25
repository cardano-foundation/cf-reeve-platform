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
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.reporting.dto.CreateCsvTemplateRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateListResponseDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.service.CsvReportTemplateService;
import org.cardanofoundation.lob.app.reporting.service.ReportTemplateService;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class ReportTemplateControllerTest {

    @Mock
    private ReportTemplateService reportTemplateService;
    @Mock
    private CsvReportTemplateService csvReportTemplateService;
    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;
    @Mock
    private JpaSortFieldValidator jpaSortFieldValidator;

    @InjectMocks
    private ReportTemplateController reportTemplateController;

    private ReportTemplateDto templateDto;
    private ReportTemplateResponseDto templateResponseDto;

    @BeforeEach
    void setUp() {
        templateDto = new ReportTemplateDto();
        templateDto.setName("Test Template");
        templateDto.setOrganisationId("org123");
        templateDto.setFields(new ArrayList<>());

        templateResponseDto = new ReportTemplateResponseDto();
        templateResponseDto.setId("abc");
        templateResponseDto.setName("Test Template");
        templateResponseDto.setOrganisationId("org123");
    }

    @Test
    void create_Success() {
        // Given
        when(reportTemplateService.create(any(ReportTemplateDto.class)))
                .thenReturn(Either.right(templateResponseDto));

        // When
        ResponseEntity<?> response = reportTemplateController.create(templateDto);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(templateResponseDto, response.getBody());
        verify(reportTemplateService).create(any(ReportTemplateDto.class));
    }

    @Test
    void create_TemplateAlreadyExists() {
        // Given
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Template Already Exists");
        ReportTemplateResponseDto reportTemplateResponseDto = ReportTemplateResponseDto.builder().error(Optional.of(problem)).build();
        when(reportTemplateService.create(any(ReportTemplateDto.class)))
                .thenReturn(Either.left(problem));

        // When
        ResponseEntity<?> response = reportTemplateController.create(templateDto);

        // Then
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(reportTemplateResponseDto, response.getBody());
    }

    @Test
    void create_ServiceError() {
        // Given
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation Error");
        ReportTemplateResponseDto reportTemplateResponseDto = ReportTemplateResponseDto.builder().error(Optional.of(problem)).build();
        when(reportTemplateService.create(any(ReportTemplateDto.class)))
                .thenReturn(Either.left(problem));

        // When
        ResponseEntity<?> response = reportTemplateController.create(templateDto);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(reportTemplateResponseDto, response.getBody());
    }

    @Test
    void update_Success() {
        // Given
        when(reportTemplateService.update(any(ReportTemplateDto.class)))
                .thenReturn(Either.right(templateResponseDto));

        // When
        ResponseEntity<?> response = reportTemplateController.update(templateDto);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(templateResponseDto, response.getBody());
        verify(reportTemplateService).update(any(ReportTemplateDto.class));
    }

    @Test
    void update_TemplateNotFound() {
        // Given
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Template Not Found");

        when(reportTemplateService.update(any(ReportTemplateDto.class)))
                .thenReturn(Either.left(problem));

        // When
        ResponseEntity<?> response = reportTemplateController.update(templateDto);

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(problem, response.getBody());
    }

    @Test
    void update_ServiceError() {
        // Given
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation Error");

        when(reportTemplateService.update(any(ReportTemplateDto.class)))
                .thenReturn(Either.left(problem));

        // When
        ResponseEntity<?> response = reportTemplateController.update(templateDto);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(problem, response.getBody());
    }

    @Test
    void findById_Success() {
        // Given
        templateResponseDto.setOrganisationId("org123");
        when(reportTemplateService.findById("abc")).thenReturn(Optional.of(templateResponseDto));
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);

        // When
        ResponseEntity<?> response = reportTemplateController.findById("abc");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(templateResponseDto, response.getBody());
    }

    @Test
    void findById_NotFound() {
        // Given
        when(reportTemplateService.findById("abc")).thenReturn(Optional.empty());

        // When
        ResponseEntity<?> response = reportTemplateController.findById("abc");

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void findById_NoOrganisationAccess() {
        // Given
        templateResponseDto.setOrganisationId("org123");
        when(reportTemplateService.findById("abc")).thenReturn(Optional.of(templateResponseDto));
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        // When
        ResponseEntity<?> response = reportTemplateController.findById("abc");

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void findAll_Success() {
        // Given
        List<ReportTemplateResponseDto> templates = List.of(templateResponseDto);
        ReportTemplateListResponseDto responseDto = ReportTemplateListResponseDto.builder().templates(templates).build();
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
        when(jpaSortFieldValidator.convertPageable(any(), any(), any())).thenReturn(Either.right(Pageable.unpaged()));
        when(reportTemplateService.findAll("org123", null, null, null, null, null, Pageable.unpaged())).thenReturn(responseDto);

        // When
        ResponseEntity<?> response = reportTemplateController.findAll("org123", null, null, null, null, null, null);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(responseDto, response.getBody());
    }

    @Test
    void findAll_NoOrganisationAccess() {
        // Given
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        // When
        ResponseEntity<?> response = reportTemplateController.findAll("org123", null, null, null, null, null, null);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(reportTemplateService, never()).findByOrganisationId(any());
    }

    @Test
    void delete_Success() {
        // Given
        templateResponseDto.setOrganisationId("org123");
        when(reportTemplateService.findById("abc")).thenReturn(Optional.of(templateResponseDto));
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
        when(reportTemplateService.delete("abc")).thenReturn(Either.right(null));

        // When
        ResponseEntity<?> response = reportTemplateController.delete("abc");

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(reportTemplateService).delete("abc");
    }

    @Test
    void delete_TemplateNotFound() {
        // Given
        when(reportTemplateService.findById("abc")).thenReturn(Optional.empty());

        // When
        ResponseEntity<?> response = reportTemplateController.delete("abc");

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(reportTemplateService, never()).delete(anyString());
    }

    @Test
    void delete_NoOrganisationAccess() {
        // Given
        templateResponseDto.setOrganisationId("org123");
        when(reportTemplateService.findById("abc")).thenReturn(Optional.of(templateResponseDto));
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        // When
        ResponseEntity<?> response = reportTemplateController.delete("abc");

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(reportTemplateService, never()).delete(anyString());
    }

    @Test
    void delete_CannotDeleteTemplateWithReports() {
        // Given
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);

        templateResponseDto.setOrganisationId("org123");
        when(reportTemplateService.findById("abc")).thenReturn(Optional.of(templateResponseDto));
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
        when(reportTemplateService.delete("abc")).thenReturn(Either.left(problem));

        // When
        ResponseEntity<?> response = reportTemplateController.delete("abc");

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(problem, response.getBody());
    }

    @Test
    void templateCreateCsv_error() {
        CreateCsvTemplateRequest csvRequest = mock(CreateCsvTemplateRequest.class);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        when(csvReportTemplateService.createCsvTemplates(csvRequest)).thenReturn(Either.left(problem));

        ResponseEntity<List<ReportTemplateResponseDto>> listResponseEntity = reportTemplateController.templateCreateCsv(csvRequest);
        assertEquals(HttpStatus.BAD_REQUEST, listResponseEntity.getStatusCode());
        assertEquals(1, listResponseEntity.getBody().size());
        assertEquals(problem, listResponseEntity.getBody().getFirst().getError().get());
    }

    @Test
    void templateCreateCsv_success() {
        CreateCsvTemplateRequest csvRequest = mock(CreateCsvTemplateRequest.class);
        ReportTemplateResponseDto templateResponseDto = mock(ReportTemplateResponseDto.class);
        when(csvReportTemplateService.createCsvTemplates(csvRequest)).thenReturn(Either.right(List.of(templateResponseDto)));

        ResponseEntity<List<ReportTemplateResponseDto>> listResponseEntity = reportTemplateController.templateCreateCsv(csvRequest);
        assertEquals(HttpStatus.CREATED, listResponseEntity.getStatusCode());
        assertEquals(1, listResponseEntity.getBody().size());
        assertEquals(templateResponseDto, listResponseEntity.getBody().getFirst());
    }
}
