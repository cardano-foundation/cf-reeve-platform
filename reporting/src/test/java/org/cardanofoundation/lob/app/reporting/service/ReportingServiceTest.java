package org.cardanofoundation.lob.app.reporting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemRepository;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.reporting.dto.ReportDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportGenerateRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.mapper.ReportMapper;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;
import org.cardanofoundation.lob.app.support.security.AuthenticationUserService;

@ExtendWith(MockitoExtension.class)
class ReportingServiceTest {

    @Mock
    private ReportingRepository reportRepository;
    @Mock
    private ReportTemplateRepository reportTemplateRepository;
    @Mock
    private ReportMapper reportMapper;
    @Mock
    private ChartOfAccountRepository chartOfAccountRepository;
    @Mock
    private TransactionItemRepository transactionItemRepository;
    @Mock
    private AuthenticationUserService authenticationUserService;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private ReportingService reportingService;

    private ReportDto reportDto;
    private ReportTemplateEntity templateEntity;
    private ReportEntity reportEntity;
    private ReportResponseDto reportResponseDto;

    @BeforeEach
    void setUp() {
        // Setup test data
        reportDto = new ReportDto();
        reportDto.setName("Test Report");
        reportDto.setOrganisationId("org123");
        reportDto.setReportTemplateId("abc");
        reportDto.setIntervalType("MONTH");
        reportDto.setYear((short) 2024);
        reportDto.setPeriod((short) 1);
        reportDto.setDataMode("SYSTEM");
        reportDto.setFields(new ArrayList<>());

        templateEntity = new ReportTemplateEntity();
        templateEntity.setId("abc");
        templateEntity.setOrganisationId("org123");
        templateEntity.setName("Test Template");
        templateEntity.setFields(List.of());
        templateEntity.setValidationRules(List.of());
        reportEntity = new ReportEntity();
        reportEntity.setId("abc");
        reportEntity.setName("Test Report");
        reportEntity.setOrganisationId("org123");
        reportEntity.setVer(1L);
        reportEntity.setReportTemplate(templateEntity);

        reportResponseDto = new ReportResponseDto();
        reportResponseDto.setId("abc");
        reportResponseDto.setName("Test Report");

    }

//    @Test
//    void create_validationRuleError() {
//        // Given
//        ReportTemplateValidationRuleEntity rule = mock(ReportTemplateValidationRuleEntity.class);
//        templateEntity.setValidationRules(List.of(rule));
//
//        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.of(templateEntity));
//        lenient().when(chartOfAccountRepository.findAllByOrganisationIdSubTypeIds(any())).thenReturn(new HashSet<>());
//        lenient().when(transactionItemRepository.findTransactionItemsByAccountCodeAndDateRange(
//                any(), any(), any())).thenReturn(new ArrayList<>());
//        when(reportRepository.findLatestByTemplateAndPeriod(
//                eq("org123"), eq("abc"), any(IntervalType.class), eq((short) 2024), eq((short) 1)))
//                .thenReturn(Optional.empty());
//        when(reportMapper.toEntity(any(ReportDto.class), isNull(), eq(templateEntity))).thenReturn(reportEntity);
//        when(reportRepository.save(any(ReportEntity.class))).thenReturn(reportEntity);
//        when(reportMapper.toResponseDto(any(ReportEntity.class))).thenReturn(reportResponseDto);
//        reportResponseDto.setError(Optional.empty());
//
//        // When
//        ReportResponseDto result = reportingService.create(reportDto);
//
//        // Then
//        assertTrue(result.getError().isEmpty());
//        assertEquals("Test Report", result.getName());
//        verify(reportRepository).save(any(ReportEntity.class));
//    }

    @Test
    void create_Success() {
        // Given
        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.of(templateEntity));
        lenient().when(chartOfAccountRepository.findAllByOrganisationIdSubTypeIds(any())).thenReturn(new HashSet<>());
        lenient().when(transactionItemRepository.findTransactionItemsByAccountCodeAndDateRange(
                any(), any(), any())).thenReturn(new ArrayList<>());
        when(reportRepository.findLatestByTemplateAndPeriod(
                eq("org123"), eq("abc"), any(IntervalType.class), eq((short) 2024), eq((short) 1)))
                .thenReturn(Optional.empty());
        when(reportMapper.toEntity(any(ReportDto.class), isNull(), eq(templateEntity))).thenReturn(reportEntity);
        when(reportRepository.save(any(ReportEntity.class))).thenReturn(reportEntity);
        when(reportMapper.toResponseDto(any(ReportEntity.class))).thenReturn(reportResponseDto);
        reportResponseDto.setError(Optional.empty());

        // When
        ReportResponseDto result = reportingService.create(reportDto);

        // Then
        assertTrue(result.getError().isEmpty());
        assertEquals("Test Report", result.getName());
        verify(reportRepository).save(any(ReportEntity.class));
    }

    @Test
    void create_TemplateNotFound() {
        // Given
        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.empty());

        // When
        ReportResponseDto result = reportingService.create(reportDto);

        // Then
        assertTrue(result.getError().isPresent());
        assertEquals("REPORT_TEMPLATE_NOT_FOUND", result.getError().get().getTitle());
        verify(reportRepository, never()).save(any());
    }

    @Test
    void create_OrganisationMismatch() {
        // Given
        templateEntity.setOrganisationId("different-org");
        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.of(templateEntity));

        // When
        ReportResponseDto result = reportingService.create(reportDto);

        // Then
        assertTrue(result.getError().isPresent());
        assertEquals("ORGANISATION_MISMATCH", result.getError().get().getTitle());
        verify(reportRepository, never()).save(any());
    }

    @Test
    void create_ReportTemplateInActive() {
        // Given
        templateEntity.setActive(false);
        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.of(templateEntity));

        // When
        ReportResponseDto result = reportingService.create(reportDto);

        // Then
        assertTrue(result.getError().isPresent());
        assertEquals("TEMPLATE_INACTIVE", result.getError().get().getTitle());
        verify(reportRepository, never()).save(any());
    }

    @Test
    void create_OverwriteUnpublishedReport() {
        // Given
        ReportEntity existingReport = new ReportEntity();
        existingReport.setId("99");
        existingReport.setLedgerDispatchApproved(false);
        existingReport.setVer(1L);
        existingReport.setReportTemplate(templateEntity);

        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.of(templateEntity));
        lenient().when(chartOfAccountRepository.findAllByOrganisationIdSubTypeIds(any())).thenReturn(new HashSet<>());
        lenient().when(transactionItemRepository.findTransactionItemsByAccountCodeAndDateRange(
                any(), any(), any())).thenReturn(new ArrayList<>());
        when(reportRepository.findLatestByTemplateAndPeriod(
                eq("org123"), eq("abc"), eq(IntervalType.MONTH), eq((short) 2024), eq((short) 1)))
                .thenReturn(Optional.of(existingReport));
        when(reportMapper.toEntity(any(ReportDto.class), eq(existingReport), eq(templateEntity)))
                .thenReturn(existingReport);
        when(reportRepository.save(any(ReportEntity.class))).thenReturn(existingReport);
        when(reportMapper.toResponseDto(any(ReportEntity.class))).thenReturn(reportResponseDto);
        reportResponseDto.setError(Optional.empty());

        // When
        ReportResponseDto result = reportingService.create(reportDto);

        // Then
        assertTrue(result.getError().isEmpty());
        verify(reportMapper).toEntity(any(ReportDto.class), eq(existingReport), eq(templateEntity));
    }

    @Test
    void create_CreateNewVersionWhenPublished() {
        // Given
        ReportEntity publishedReport = new ReportEntity();
        publishedReport.setId("99");
        publishedReport.setLedgerDispatchApproved(true);
        publishedReport.setVer(1L);
        publishedReport.setReportTemplate(templateEntity);

        ReportEntity newReport = new ReportEntity();
        newReport.setReportTemplate(templateEntity);

        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.of(templateEntity));
        lenient().when(chartOfAccountRepository.findAllByOrganisationIdSubTypeIds(any())).thenReturn(new HashSet<>());
        lenient().when(transactionItemRepository.findTransactionItemsByAccountCodeAndDateRange(
                any(), any(), any())).thenReturn(new ArrayList<>());
        when(reportRepository.findLatestByTemplateAndPeriod(
                eq("org123"), eq("abc"), eq(IntervalType.MONTH), eq((short) 2024), eq((short) 1)))
                .thenReturn(Optional.of(publishedReport));
        when(reportMapper.toEntity(any(ReportDto.class), isNull(), eq(templateEntity)))
                .thenReturn(newReport);
        when(reportRepository.save(any(ReportEntity.class))).thenReturn(newReport);
        when(reportMapper.toResponseDto(any(ReportEntity.class))).thenReturn(reportResponseDto);
        reportResponseDto.setError(Optional.empty());

        // When
        ReportResponseDto result = reportingService.create(reportDto);

        // Then
        assertTrue(result.getError().isEmpty());
        verify(reportMapper).toEntity(any(ReportDto.class), isNull(), eq(templateEntity));
        assertEquals(2L, newReport.getVer());
    }

    @Test
    void delete_Success() {
        // Given
        ReportEntity report = new ReportEntity();
        report.setId("abc");
        report.setLedgerDispatchApproved(false);

        when(reportRepository.findById("abc")).thenReturn(Optional.of(report));

        // When
        Either<Problem, Void> result = reportingService.delete("abc");

        // Then
        assertTrue(result.isRight());
        verify(reportRepository).deleteById("abc");
    }

    @Test
    void delete_ReportNotFound() {
        // Given
        when(reportRepository.findById("abc")).thenReturn(Optional.empty());

        // When
        Either<Problem, Void> result = reportingService.delete("abc");

        // Then
        assertTrue(result.isLeft());
        assertEquals("REPORT_NOT_FOUND", result.getLeft().getTitle());
        verify(reportRepository, never()).deleteById(anyString());
    }

    @Test
    void delete_PublishedReportCannotBeDeleted() {
        // Given
        ReportEntity publishedReport = new ReportEntity();
        publishedReport.setId("abc");
        publishedReport.setLedgerDispatchApproved(true);

        when(reportRepository.findById("abc")).thenReturn(Optional.of(publishedReport));

        // When
        Either<Problem, Void> result = reportingService.delete("abc");

        // Then
        assertTrue(result.isLeft());
        assertEquals("REPORT_ALREADY_PUBLISHED", result.getLeft().getTitle());
        verify(reportRepository, never()).deleteById(anyString());
    }

    @Test
    void findById_Success() {
        // Given
        when(reportRepository.findById("abc")).thenReturn(Optional.of(reportEntity));
        when(reportMapper.toResponseDto(reportEntity)).thenReturn(reportResponseDto);

        // When
        Optional<ReportResponseDto> result = reportingService.findById("abc");

        // Then
        assertTrue(result.isPresent());
        assertEquals("Test Report", result.get().getName());
    }

    @Test
    void findById_NotFound() {
        // Given
        when(reportRepository.findById("abc")).thenReturn(Optional.empty());

        // When
        Optional<ReportResponseDto> result = reportingService.findById("abc");

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void findByOrganisationId_Success() {
        // Given
        List<ReportEntity> reports = List.of(reportEntity);
        when(reportRepository.findByOrganisationId("org123")).thenReturn(reports);
        when(reportMapper.toResponseDto(reportEntity)).thenReturn(reportResponseDto);

        // When
        List<ReportResponseDto> result = reportingService.findByOrganisationId("org123");

        // Then
        assertEquals(1, result.size());
        assertEquals("Test Report", result.get(0).getName());
    }

    @Test
    void findByReportTemplateId_Success() {
        // Given
        List<ReportEntity> reports = List.of(reportEntity);
        when(reportRepository.findByReportTemplateId("abc")).thenReturn(reports);
        when(reportMapper.toResponseDto(reportEntity)).thenReturn(reportResponseDto);

        // When
        List<ReportResponseDto> result = reportingService.findByReportTemplateId("abc");

        // Then
        assertEquals(1, result.size());
        assertEquals("Test Report", result.get(0).getName());
    }

    @Test
    void generate_Success() {
        // Given
        ReportGenerateRequest request = new ReportGenerateRequest();
        request.setReportTemplateId("abc");
        request.setOrganisationId("org123");
        request.setIntervalType("MONTH");
        request.setYear((short) 2024);
        request.setPeriod((short) 1);

        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.of(templateEntity));
        when(reportMapper.toEntity(any(ReportDto.class), isNull(), eq(templateEntity))).thenReturn(reportEntity);
        when(reportMapper.toResponseDto(any(ReportEntity.class))).thenReturn(reportResponseDto);

        // When
        Either<Problem, ReportResponseDto> result = reportingService.generate(request);

        // Then
        assertTrue(result.isRight());
        assertEquals("Test Report", result.get().getName());
        verify(reportRepository, never()).save(any());
    }

    @Test
    void generate_TemplateNotFound() {
        // Given
        ReportGenerateRequest request = new ReportGenerateRequest();
        request.setReportTemplateId("abc");
        request.setOrganisationId("org123");

        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.empty());

        // When
        Either<Problem, ReportResponseDto> result = reportingService.generate(request);

        // Then
        assertTrue(result.isLeft());
        assertEquals("REPORT_TEMPLATE_NOT_FOUND", result.getLeft().getTitle());
    }
}
