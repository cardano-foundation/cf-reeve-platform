package org.cardanofoundation.lob.app.reporting.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        reportDto.setReportTemplateId(1L);
        reportDto.setIntervalType("MONTHLY");
        reportDto.setYear((short) 2024);
        reportDto.setPeriod((short) 1);
        reportDto.setFields(new ArrayList<>());

        templateEntity = new ReportTemplateEntity();
        templateEntity.setId(1L);
        templateEntity.setOrganisationId("org123");
        templateEntity.setName("Test Template");
        templateEntity.setColumns(new ArrayList<>());

        reportEntity = new ReportEntity();
        reportEntity.setId(1L);
        reportEntity.setName("Test Report");
        reportEntity.setOrganisationId("org123");
        reportEntity.setVer(1L);

        reportResponseDto = new ReportResponseDto();
        reportResponseDto.setId(1L);
        reportResponseDto.setName("Test Report");
    }

    @Test
    void create_Success() {
        // Given
        when(reportTemplateRepository.findById(1L)).thenReturn(Optional.of(templateEntity));
        when(reportMapper.toEntity(any(ReportDto.class), any(), eq(templateEntity))).thenReturn(reportEntity);
        when(reportRepository.save(any(ReportEntity.class))).thenReturn(reportEntity);
        when(reportMapper.toResponseDto(any(ReportEntity.class))).thenReturn(reportResponseDto);

        // When
        Either<Problem, ReportResponseDto> result = reportingService.create(reportDto, true);

        // Then
        assertTrue(result.isRight());
        assertEquals("Test Report", result.get().getName());
        verify(reportRepository).save(any(ReportEntity.class));
    }

    @Test
    void create_TemplateNotFound() {
        // Given
        when(reportTemplateRepository.findById(1L)).thenReturn(Optional.empty());

        // When
        Either<Problem, ReportResponseDto> result = reportingService.create(reportDto, true);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Report Template Not Found", result.getLeft().getTitle());
        verify(reportRepository, never()).save(any());
    }

    @Test
    void create_OrganisationMismatch() {
        // Given
        templateEntity.setOrganisationId("different-org");
        when(reportTemplateRepository.findById(1L)).thenReturn(Optional.of(templateEntity));

        // When
        Either<Problem, ReportResponseDto> result = reportingService.create(reportDto, true);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Organisation Mismatch", result.getLeft().getTitle());
        verify(reportRepository, never()).save(any());
    }

    @Test
    void create_OverwriteUnpublishedReport() {
        // Given
        ReportEntity existingReport = new ReportEntity();
        existingReport.setId(99L);
        existingReport.setLedgerDispatchApproved(false);
        existingReport.setVer(1L);

        when(reportTemplateRepository.findById(1L)).thenReturn(Optional.of(templateEntity));
        when(reportRepository.findLatestByTemplateAndPeriod(
                eq("org123"), eq(1L), eq(IntervalType.MONTHLY), eq((short) 2024), eq((short) 1)))
                .thenReturn(Optional.of(existingReport));
        when(reportMapper.toEntity(any(ReportDto.class), eq(existingReport), eq(templateEntity)))
                .thenReturn(existingReport);
        when(reportRepository.save(any(ReportEntity.class))).thenReturn(existingReport);
        when(reportMapper.toResponseDto(any(ReportEntity.class))).thenReturn(reportResponseDto);

        // When
        Either<Problem, ReportResponseDto> result = reportingService.create(reportDto, true);

        // Then
        assertTrue(result.isRight());
        verify(reportMapper).toEntity(any(ReportDto.class), eq(existingReport), eq(templateEntity));
    }

    @Test
    void create_CreateNewVersionWhenPublished() {
        // Given
        ReportEntity publishedReport = new ReportEntity();
        publishedReport.setId(99L);
        publishedReport.setLedgerDispatchApproved(true);
        publishedReport.setVer(1L);

        ReportEntity newReport = new ReportEntity();

        when(reportTemplateRepository.findById(1L)).thenReturn(Optional.of(templateEntity));
        when(reportRepository.findLatestByTemplateAndPeriod(
                eq("org123"), eq(1L), eq(IntervalType.MONTHLY), eq((short) 2024), eq((short) 1)))
                .thenReturn(Optional.of(publishedReport));
        when(reportMapper.toEntity(any(ReportDto.class), isNull(), eq(templateEntity)))
                .thenReturn(newReport);
        when(reportRepository.save(any(ReportEntity.class))).thenReturn(newReport);
        when(reportMapper.toResponseDto(any(ReportEntity.class))).thenReturn(reportResponseDto);

        // When
        Either<Problem, ReportResponseDto> result = reportingService.create(reportDto, true);

        // Then
        assertTrue(result.isRight());
        verify(reportMapper).toEntity(any(ReportDto.class), isNull(), eq(templateEntity));
        assertEquals(2L, newReport.getVer());
    }

    @Test
    void delete_Success() {
        // Given
        ReportEntity report = new ReportEntity();
        report.setId(1L);
        report.setLedgerDispatchApproved(false);

        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        // When
        Either<Problem, Void> result = reportingService.delete(1L);

        // Then
        assertTrue(result.isRight());
        verify(reportRepository).deleteById(1L);
    }

    @Test
    void delete_ReportNotFound() {
        // Given
        when(reportRepository.findById(1L)).thenReturn(Optional.empty());

        // When
        Either<Problem, Void> result = reportingService.delete(1L);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Report Not Found", result.getLeft().getTitle());
        verify(reportRepository, never()).deleteById(anyLong());
    }

    @Test
    void delete_PublishedReportCannotBeDeleted() {
        // Given
        ReportEntity publishedReport = new ReportEntity();
        publishedReport.setId(1L);
        publishedReport.setLedgerDispatchApproved(true);

        when(reportRepository.findById(1L)).thenReturn(Optional.of(publishedReport));

        // When
        Either<Problem, Void> result = reportingService.delete(1L);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Report Already Published", result.getLeft().getTitle());
        verify(reportRepository, never()).deleteById(anyLong());
    }

    @Test
    void findById_Success() {
        // Given
        when(reportRepository.findById(1L)).thenReturn(Optional.of(reportEntity));
        when(reportMapper.toResponseDto(reportEntity)).thenReturn(reportResponseDto);

        // When
        Optional<ReportResponseDto> result = reportingService.findById(1L);

        // Then
        assertTrue(result.isPresent());
        assertEquals("Test Report", result.get().getName());
    }

    @Test
    void findById_NotFound() {
        // Given
        when(reportRepository.findById(1L)).thenReturn(Optional.empty());

        // When
        Optional<ReportResponseDto> result = reportingService.findById(1L);

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
        when(reportRepository.findByReportTemplateId(1L)).thenReturn(reports);
        when(reportMapper.toResponseDto(reportEntity)).thenReturn(reportResponseDto);

        // When
        List<ReportResponseDto> result = reportingService.findByReportTemplateId(1L);

        // Then
        assertEquals(1, result.size());
        assertEquals("Test Report", result.get(0).getName());
    }

    @Test
    void generate_Success() {
        // Given
        ReportGenerateRequest request = new ReportGenerateRequest();
        request.setReportTemplateId(1L);
        request.setOrganisationId("org123");
        request.setIntervalType("MONTHLY");
        request.setYear((short) 2024);
        request.setPeriod((short) 1);

        when(reportTemplateRepository.findById(1L)).thenReturn(Optional.of(templateEntity));
        when(reportMapper.toEntity(any(ReportDto.class), any(), eq(templateEntity))).thenReturn(reportEntity);
        when(reportRepository.save(any(ReportEntity.class))).thenReturn(reportEntity);
        when(reportMapper.toResponseDto(any(ReportEntity.class))).thenReturn(reportResponseDto);

        // When
        Either<Problem, ReportResponseDto> result = reportingService.generate(request);

        // Then
        assertTrue(result.isRight());
        assertEquals("Test Report", result.get().getName());
    }

    @Test
    void generate_TemplateNotFound() {
        // Given
        ReportGenerateRequest request = new ReportGenerateRequest();
        request.setReportTemplateId(1L);
        request.setOrganisationId("org123");

        when(reportTemplateRepository.findById(1L)).thenReturn(Optional.empty());

        // When
        Either<Problem, ReportResponseDto> result = reportingService.generate(request);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Report Template Not Found", result.getLeft().getTitle());
    }
}
