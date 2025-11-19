package org.cardanofoundation.lob.app.reporting.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.service.ReportTemplateService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class ReportTemplateControllerTest {

    @Mock
    private ReportTemplateService reportTemplateService;

    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;

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
        templateResponseDto.setId(1L);
        templateResponseDto.setName("Test Template");
        templateResponseDto.setOrganisationId("org123");
    }

    @Test
    void create_Success() {
        // Given
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
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
    void create_NoOrganisationAccess() {
        // Given
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        // When
        ResponseEntity<?> response = reportTemplateController.create(templateDto);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(reportTemplateService, never()).create(any());
    }

    @Test
    void create_ServiceError() {
        // Given
        Problem problem = Problem.builder()
                .withTitle("Validation Error")
                .withStatus(Status.BAD_REQUEST)
                .build();

        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
        when(reportTemplateService.create(any(ReportTemplateDto.class)))
                .thenReturn(Either.left(problem));

        // When
        ResponseEntity<?> response = reportTemplateController.create(templateDto);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(problem, response.getBody());
    }

    @Test
    void findById_Success() {
        // Given
        templateResponseDto.setOrganisationId("org123");
        when(reportTemplateService.findById(1L)).thenReturn(Optional.of(templateResponseDto));
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);

        // When
        ResponseEntity<?> response = reportTemplateController.findById(1L);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(templateResponseDto, response.getBody());
    }

    @Test
    void findById_NotFound() {
        // Given
        when(reportTemplateService.findById(1L)).thenReturn(Optional.empty());

        // When
        ResponseEntity<?> response = reportTemplateController.findById(1L);

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void findById_NoOrganisationAccess() {
        // Given
        templateResponseDto.setOrganisationId("org123");
        when(reportTemplateService.findById(1L)).thenReturn(Optional.of(templateResponseDto));
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        // When
        ResponseEntity<?> response = reportTemplateController.findById(1L);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void findAll_Success() {
        // Given
        List<ReportTemplateResponseDto> templates = List.of(templateResponseDto);
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
        when(reportTemplateService.findByOrganisationId("org123")).thenReturn(templates);

        // When
        ResponseEntity<?> response = reportTemplateController.findAll("org123");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(templates, response.getBody());
    }

    @Test
    void findAll_NoOrganisationAccess() {
        // Given
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        // When
        ResponseEntity<?> response = reportTemplateController.findAll("org123");

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(reportTemplateService, never()).findByOrganisationId(any());
    }

    @Test
    void delete_Success() {
        // Given
        templateResponseDto.setOrganisationId("org123");
        when(reportTemplateService.findById(1L)).thenReturn(Optional.of(templateResponseDto));
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
        when(reportTemplateService.delete(1L)).thenReturn(Either.right(null));

        // When
        ResponseEntity<?> response = reportTemplateController.delete(1L);

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(reportTemplateService).delete(1L);
    }

    @Test
    void delete_TemplateNotFound() {
        // Given
        when(reportTemplateService.findById(1L)).thenReturn(Optional.empty());

        // When
        ResponseEntity<?> response = reportTemplateController.delete(1L);

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(reportTemplateService, never()).delete(anyLong());
    }

    @Test
    void delete_NoOrganisationAccess() {
        // Given
        templateResponseDto.setOrganisationId("org123");
        when(reportTemplateService.findById(1L)).thenReturn(Optional.of(templateResponseDto));
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        // When
        ResponseEntity<?> response = reportTemplateController.delete(1L);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(reportTemplateService, never()).delete(anyLong());
    }

    @Test
    void delete_CannotDeleteTemplateWithReports() {
        // Given
        Problem problem = Problem.builder()
                .withTitle("Template Has Associated Reports")
                .withStatus(Status.BAD_REQUEST)
                .build();

        templateResponseDto.setOrganisationId("org123");
        when(reportTemplateService.findById(1L)).thenReturn(Optional.of(templateResponseDto));
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(true);
        when(reportTemplateService.delete(1L)).thenReturn(Either.left(problem));

        // When
        ResponseEntity<?> response = reportTemplateController.delete(1L);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(problem, response.getBody());
    }
}
