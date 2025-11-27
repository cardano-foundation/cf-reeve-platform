package org.cardanofoundation.lob.app.reporting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountTypeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.dto.TemplateCsvLine;
import org.cardanofoundation.lob.app.reporting.mapper.ReportTemplateMapper;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;

@ExtendWith(MockitoExtension.class)
class ReportTemplateServiceTest {

    @Mock
    private ReportTemplateRepository reportTemplateRepository;
    @Mock
    private ReportTemplateMapper reportTemplateMapper;
    @Mock
    private ReportingRepository reportingRepository;
    @Mock
    private Validator validator;
    @Mock
    private CsvParser<TemplateCsvLine> csvParser;
    @Mock
    private ChartOfAccountTypeRepository chartOfAccountTypeRepository;
    @Mock
    private ChartOfAccountSubTypeRepository chartOfAccountSubTypeRepository;
    @Mock
    private OrganisationPublicApiIF organisationPublicApi;

    @InjectMocks
    private ReportTemplateService reportTemplateService;

    private ReportTemplateDto templateDto;
    private ReportTemplateEntity templateEntity;
    private ReportTemplateResponseDto templateResponseDto;

    @BeforeEach
    void setUp() {
        // Setup test data
        templateDto = new ReportTemplateDto();
        templateDto.setName("Test Template");
        templateDto.setOrganisationId("org123");
        templateDto.setFields(new ArrayList<>());

        templateEntity = new ReportTemplateEntity();
        templateEntity.setId("abc");
        templateEntity.setOrganisationId("org123");
        templateEntity.setName("Test Template");
        templateEntity.setVer(1L);
        templateEntity.setColumns(new ArrayList<>());

        templateResponseDto = new ReportTemplateResponseDto();
        templateResponseDto.setId("abc");
        templateResponseDto.setName("Test Template");
    }

    @Test
    void create_NewTemplate_Success() {

        // Given
        when(reportTemplateRepository.findLatestByOrganisationIdAndName("org123", "Test Template"))
                .thenReturn(Optional.empty());
        when(reportTemplateMapper.toEntity(eq(templateDto), isNull())).thenReturn(templateEntity);
        when(reportTemplateRepository.save(any(ReportTemplateEntity.class))).thenReturn(templateEntity);
        when(reportTemplateMapper.toResponseDto(templateEntity)).thenReturn(templateResponseDto);

        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        // When
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(templateDto);

        // Then
        assertTrue(result.isRight());
        assertEquals("Test Template", result.get().getName());
        verify(reportTemplateRepository).save(templateEntity);
    }

    @Test
    void create_NewTemplate_ValidationError() {

        Errors errors = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(validator.validateObject(any())).thenReturn(errors);
        when(objectError.getDefaultMessage()).thenReturn("Default Message");

        // When
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(templateDto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Default Message", result.getLeft().getDetail());
    }

    @Test
    void create_NewTemplate_ValidationErrorChilds() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto fieldDto = mock(ReportTemplateFieldDto.class);
        Errors errors = mock(Errors.class);
        Errors errorsDto = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);

        when(dto.getFields()).thenReturn(List.of(fieldDto));
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(validator.validateObject(any(ReportTemplateDto.class))).thenReturn(errorsDto);
        when(validator.validateObject(any(ReportTemplateFieldDto.class))).thenReturn(errors);
        when(errorsDto.getAllErrors()).thenReturn(List.of());
        when(objectError.getDefaultMessage()).thenReturn("Default Message");

        // When
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(dto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Default Message", result.getLeft().getDetail());
    }

    @Test
    void create_NewTemplate_duplicateNames() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto parentDtoField = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto fieldDto1 = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto fieldDto2 = mock(ReportTemplateFieldDto.class);
        Errors errors = mock(Errors.class);

        when(dto.getFields()).thenReturn(List.of(parentDtoField));
        when(parentDtoField.getChildFields()).thenReturn(List.of(fieldDto1, fieldDto2));
        when(parentDtoField.getFieldName()).thenReturn("parentName");
        when(fieldDto1.getFieldName()).thenReturn("sameName");
        when(fieldDto2.getFieldName()).thenReturn("sameName");
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        // When
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(dto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Duplicate field name 'sameName' under parent 'parentName'. Field names must be unique within the same parent.", result.getLeft().getDetail());
    }

    @Test
    void create_TemplateAlreadyExists_ReturnsConflict() {
        // Given
        ReportTemplateEntity existing = new ReportTemplateEntity();
        existing.setId("abc");
        existing.setVer(1L);

        when(reportTemplateRepository.findLatestByOrganisationIdAndName("org123", "Test Template"))
                .thenReturn(Optional.of(existing));
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(templateDto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Template Already Exists", result.getLeft().getTitle());
        verify(reportTemplateRepository, never()).save(any());
    }

    @Test
    void update_ExistingTemplateWithoutReports_UpdatesInPlace() {
        // Given
        ReportTemplateEntity existing = new ReportTemplateEntity();
        existing.setId("abc");
        existing.setVer(1L);

        when(reportTemplateRepository.findLatestByOrganisationIdAndName("org123", "Test Template"))
                .thenReturn(Optional.of(existing));
        when(reportingRepository.findByReportTemplateId("abc")).thenReturn(new ArrayList<>());
        when(reportTemplateMapper.toEntity(eq(templateDto), eq(existing))).thenReturn(existing);
        when(reportTemplateRepository.save(any(ReportTemplateEntity.class))).thenReturn(existing);
        when(reportTemplateMapper.toResponseDto(existing)).thenReturn(templateResponseDto);
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);
        // When
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.update(templateDto);

        // Then
        assertTrue(result.isRight());
        verify(reportTemplateMapper).toEntity(eq(templateDto), eq(existing));
        verify(reportTemplateRepository).save(existing);
    }

    @Test
    void update_CreateNewVersionWhenReportsExist() {
        // Given
        ReportTemplateEntity existing = new ReportTemplateEntity();
        existing.setId("abc");
        existing.setVer(1L);

        ReportEntity report = new ReportEntity();
        report.setId("abc");

        ReportTemplateEntity newVersion = new ReportTemplateEntity();

        when(reportTemplateRepository.findLatestByOrganisationIdAndName("org123", "Test Template"))
                .thenReturn(Optional.of(existing));
        when(reportingRepository.findByReportTemplateId("abc")).thenReturn(List.of(report));
        when(reportTemplateMapper.toEntity(eq(templateDto), isNull())).thenReturn(newVersion);
        when(reportTemplateRepository.save(any(ReportTemplateEntity.class))).thenReturn(newVersion);
        when(reportTemplateMapper.toResponseDto(newVersion)).thenReturn(templateResponseDto);
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        // When
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.update(templateDto);

        // Then
        assertTrue(result.isRight());
        assertEquals(2L, newVersion.getVer());
        verify(reportTemplateRepository).save(newVersion);
    }

    @Test
    void update_TemplateNotFound_ReturnsNotFound() {
        // Given
        when(reportTemplateRepository.findLatestByOrganisationIdAndName("org123", "Test Template"))
                .thenReturn(Optional.empty());
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);
        // When
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.update(templateDto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Template Not Found", result.getLeft().getTitle());
        verify(reportTemplateRepository, never()).save(any());
    }

    @Test
    void delete_Success() {
        // Given
        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.of(templateEntity));
        when(reportingRepository.findByReportTemplateId("abc")).thenReturn(new ArrayList<>());

        // When
        Either<Problem, Void> result = reportTemplateService.delete("abc");

        // Then
        assertTrue(result.isRight());
        verify(reportTemplateRepository).deleteById("abc");
    }

    @Test
    void delete_TemplateNotFound() {
        // Given
        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.empty());

        // When
        Either<Problem, Void> result = reportTemplateService.delete("abc");

        // Then
        assertTrue(result.isLeft());
        assertEquals("Report Template Not Found", result.getLeft().getTitle());
        verify(reportTemplateRepository, never()).deleteById(anyString());
    }

    @Test
    void delete_TemplateHasAssociatedReports() {
        // Given
        ReportEntity report = new ReportEntity();
        report.setId("abc");

        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.of(templateEntity));
        when(reportingRepository.findByReportTemplateId("abc")).thenReturn(List.of(report));

        // When
        Either<Problem, Void> result = reportTemplateService.delete("abc");

        // Then
        assertTrue(result.isLeft());
        assertEquals("Template Has Associated Reports", result.getLeft().getTitle());
        verify(reportTemplateRepository, never()).deleteById(anyString());
    }

    @Test
    void findById_Success() {
        // Given
        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.of(templateEntity));
        when(reportTemplateMapper.toResponseDto(templateEntity)).thenReturn(templateResponseDto);

        // When
        Optional<ReportTemplateResponseDto> result = reportTemplateService.findById("abc");
        // Then
        assertTrue(result.isPresent());
        assertEquals("Test Template", result.get().getName());
    }

    @Test
    void findById_NotFound() {
        // Given
        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.empty());

        // When
        Optional<ReportTemplateResponseDto> result = reportTemplateService.findById("abc");

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void findByOrganisationId_Success() {
        // Given
        List<ReportTemplateEntity> templates = List.of(templateEntity);
        when(reportTemplateRepository.findByOrganisationId("org123")).thenReturn(templates);
        when(reportTemplateMapper.toResponseDto(templateEntity)).thenReturn(templateResponseDto);

        // When
        List<ReportTemplateResponseDto> result = reportTemplateService.findByOrganisationId("org123");

        // Then
        assertEquals(1, result.size());
        assertEquals("Test Template", result.get(0).getName());
    }

    @Test
    void findAll_Success() {
        // Given
        List<ReportTemplateEntity> templates = List.of(templateEntity);
        when(reportTemplateRepository.findAll()).thenReturn(templates);
        when(reportTemplateMapper.toResponseDto(templateEntity)).thenReturn(templateResponseDto);

        // When
        List<ReportTemplateResponseDto> result = reportTemplateService.findAll();

        // Then
        assertEquals(1, result.size());
        assertEquals("Test Template", result.get(0).getName());
    }

    @Test
    void existsByOrganisationIdAndName_True() {
        // Given
        when(reportTemplateRepository.existsByOrganisationIdAndName("org123", "Test Template"))
                .thenReturn(true);

        // When
        boolean result = reportTemplateService.existsByOrganisationIdAndName("org123", "Test Template");

        // Then
        assertTrue(result);
    }

    @Test
    void existsByOrganisationIdAndName_False() {
        // Given
        when(reportTemplateRepository.existsByOrganisationIdAndName("org123", "Test Template"))
                .thenReturn(false);

        // When
        boolean result = reportTemplateService.existsByOrganisationIdAndName("org123", "Test Template");

        // Then
        assertFalse(result);
    }



}
