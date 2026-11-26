package org.cardanofoundation.lob.app.reporting.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.reporting.dto.ReportStandardDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportStandardResponseDto;
import org.cardanofoundation.lob.app.reporting.service.ReportStandardService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class ReportStandardControllerTest {

    @Mock
    private ReportStandardService reportStandardService;

    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @InjectMocks
    private ReportStandardController reportStandardController;

    @BeforeEach
    void setUp() {
        lenient().when(keycloakSecurityHelper.canUserAccessOrg(any())).thenReturn(true);
    }

    @Test
    void getAllReportStandards_noOrgAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        ResponseEntity<List<ReportStandardResponseDto>> response = reportStandardController.getAllReportStandards("org123");

        assertEquals(403, response.getStatusCode().value());
        verifyNoInteractions(reportStandardService);
    }

    @Test
    void insertReportStandard_noOrgAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        ResponseEntity<ReportStandardResponseDto> response = reportStandardController.insertReportStandard("org123", mock(ReportStandardDto.class));

        assertEquals(403, response.getStatusCode().value());
        verifyNoInteractions(reportStandardService);
    }

    @Test
    void updateReportStandard_noOrgAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        ResponseEntity<ReportStandardResponseDto> response = reportStandardController.updateReportStandard("org123", mock(ReportStandardDto.class));

        assertEquals(403, response.getStatusCode().value());
        verifyNoInteractions(reportStandardService);
    }

    @Test
    void insertReportStandardsCsv_noOrgAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        ResponseEntity<Object> response = reportStandardController.insertReportStandardsCsv("org123", mock(MultipartFile.class));

        assertEquals(403, response.getStatusCode().value());
        verifyNoInteractions(reportStandardService);
    }

    @Test
    void getAllReportStandards_success() {
        ReportStandardResponseDto view = ReportStandardResponseDto.createSuccess("IFRS", "IFRS Label", true);
        when(reportStandardService.getAllReportStandards("org123")).thenReturn(List.of(view));

        ResponseEntity<List<ReportStandardResponseDto>> response = reportStandardController.getAllReportStandards("org123");

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("IFRS", response.getBody().get(0).getCode());
    }

    @Test
    void insertReportStandard_success() {
        ReportStandardDto update = mock(ReportStandardDto.class);
        ReportStandardResponseDto view = ReportStandardResponseDto.createSuccess("IFRS", "IFRS Label", true);
        when(reportStandardService.insertReportStandard("org123", update, false)).thenReturn(view);

        ResponseEntity<ReportStandardResponseDto> response = reportStandardController.insertReportStandard("org123", update);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals("IFRS", response.getBody().getCode());
    }

    @Test
    void insertReportStandard_error() {
        ReportStandardDto update = mock(ReportStandardDto.class);
        ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Standard with code IFRS already exists");
        ReportStandardResponseDto view = ReportStandardResponseDto.createFail(error, new ReportStandardDto("IFRS", "IFRS Label", true));
        when(reportStandardService.insertReportStandard("org123", update, false)).thenReturn(view);

        ResponseEntity<ReportStandardResponseDto> response = reportStandardController.insertReportStandard("org123", update);

        assertEquals(HttpStatus.CONFLICT.value(), response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getError().isPresent());
    }

    @Test
    void updateReportStandard_success() {
        ReportStandardDto update = mock(ReportStandardDto.class);
        ReportStandardResponseDto view = ReportStandardResponseDto.createSuccess("IFRS", "Updated Label", false);
        when(reportStandardService.updateReportStandard("org123", update)).thenReturn(view);

        ResponseEntity<ReportStandardResponseDto> response = reportStandardController.updateReportStandard("org123", update);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals("Updated Label", response.getBody().getLabel());
    }

    @Test
    void updateReportStandard_error() {
        ReportStandardDto update = mock(ReportStandardDto.class);
        ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Standard with code IFRS not found");
        ReportStandardResponseDto view = ReportStandardResponseDto.createFail(error, new ReportStandardDto("IFRS", "IFRS Label", true));
        when(reportStandardService.updateReportStandard("org123", update)).thenReturn(view);

        ResponseEntity<ReportStandardResponseDto> response = reportStandardController.updateReportStandard("org123", update);

        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getError().isPresent());
    }

    @Test
    void insertReportStandardsCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        List<ReportStandardResponseDto> views = List.of(ReportStandardResponseDto.createSuccess("IFRS", "IFRS Label", true));
        when(reportStandardService.insertViaCsv("org123", file)).thenReturn(Either.right(views));

        ResponseEntity<Object> response = reportStandardController.insertReportStandardsCsv("org123", file);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertEquals(views, response.getBody());
    }

    @Test
    void insertReportStandardsCsv_problem() {
        MultipartFile file = mock(MultipartFile.class);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "CSV parse error");
        when(reportStandardService.insertViaCsv("org123", file)).thenReturn(Either.left(problem));

        ResponseEntity<Object> response = reportStandardController.insertReportStandardsCsv("org123", file);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
        assertEquals(problem, response.getBody());
    }

}
