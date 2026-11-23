package org.cardanofoundation.lob.app.reporting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.reporting.dto.ReportStandardDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportStandardResponseDto;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportStandardEntity;
import org.cardanofoundation.lob.app.reporting.repository.ReportStandardRepository;

@ExtendWith(MockitoExtension.class)
class ReportStandardServiceTest {

    @Mock
    private ReportStandardRepository reportStandardRepository;
    @Mock
    private CsvParser<ReportStandardDto> csvParser;
    @Mock
    private Validator validator;

    @InjectMocks
    private ReportStandardService reportStandardService;

    private final String organisationId = "org123";

    @Test
    void getAllReportStandards_success() {
        ReportStandardEntity standard = new ReportStandardEntity(new ReportStandardEntity.Id(organisationId, "IFRS"), "IFRS Label", true);
        when(reportStandardRepository.findAllByOrganisationId(organisationId)).thenReturn(Set.of(standard));

        List<ReportStandardResponseDto> result = reportStandardService.getAllReportStandards(organisationId);

        assertEquals(1, result.size());
        assertEquals("IFRS", result.get(0).getCode());
        assertEquals("IFRS Label", result.get(0).getLabel());
        assertTrue(result.get(0).isActive());
    }

    @Test
    void getAllReportStandards_empty() {
        when(reportStandardRepository.findAllByOrganisationId(organisationId)).thenReturn(Set.of());

        List<ReportStandardResponseDto> result = reportStandardService.getAllReportStandards(organisationId);

        assertTrue(result.isEmpty());
    }

    @Test
    void findAllByOrganisationId_delegatesToRepository() {
        ReportStandardEntity standard = new ReportStandardEntity(new ReportStandardEntity.Id(organisationId, "IFRS"), "IFRS Label", true);
        when(reportStandardRepository.findAllByOrganisationId(organisationId)).thenReturn(Set.of(standard));

        Set<ReportStandardEntity> result = reportStandardService.findAllByOrganisationId(organisationId);

        assertEquals(Set.of(standard), result);
    }

    @Test
    void getReportStandard_found() {
        ReportStandardEntity.Id id = new ReportStandardEntity.Id(organisationId, "IFRS");
        ReportStandardEntity standard = new ReportStandardEntity(id, "IFRS Label", true);
        when(reportStandardRepository.findById(id)).thenReturn(Optional.of(standard));

        Optional<ReportStandardResponseDto> result = reportStandardService.getReportStandard(organisationId, "IFRS");

        assertTrue(result.isPresent());
        assertEquals("IFRS", result.get().getCode());
    }

    @Test
    void getReportStandard_notFound() {
        ReportStandardEntity.Id id = new ReportStandardEntity.Id(organisationId, "IFRS");
        when(reportStandardRepository.findById(id)).thenReturn(Optional.empty());

        Optional<ReportStandardResponseDto> result = reportStandardService.getReportStandard(organisationId, "IFRS");

        assertFalse(result.isPresent());
    }

    @Test
    void updateReportStandard_notFound() {
        ReportStandardDto update = new ReportStandardDto("IFRS", "New Label", false);
        when(reportStandardRepository.findById(new ReportStandardEntity.Id(organisationId, "IFRS"))).thenReturn(Optional.empty());

        ReportStandardResponseDto result = reportStandardService.updateReportStandard(organisationId, update);

        assertEquals("IFRS", result.getCode());
        assertTrue(result.getError().isPresent());
        assertEquals("REPORT_STANDARD_NOT_FOUND", result.getError().get().getTitle());
        assertEquals("Standard with code IFRS not found", result.getError().get().getDetail());
        verify(reportStandardRepository, never()).save(any());
    }

    @Test
    void updateReportStandard_success() {
        ReportStandardDto update = new ReportStandardDto("IFRS", "New Label", false);
        ReportStandardEntity existing = new ReportStandardEntity(new ReportStandardEntity.Id(organisationId, "IFRS"), "Old Label", true);
        when(reportStandardRepository.findById(new ReportStandardEntity.Id(organisationId, "IFRS"))).thenReturn(Optional.of(existing));
        when(reportStandardRepository.save(existing)).thenReturn(existing);

        ReportStandardResponseDto result = reportStandardService.updateReportStandard(organisationId, update);

        assertTrue(result.getError().isEmpty());
        assertEquals("New Label", result.getLabel());
        assertFalse(result.isActive());
        assertEquals("New Label", existing.getLabel());
        assertFalse(existing.isActive());
    }

    @Test
    void insertReportStandard_alreadyExists_noUpsert() {
        ReportStandardDto update = new ReportStandardDto("IFRS", "IFRS Label", true);
        ReportStandardEntity existing = new ReportStandardEntity(new ReportStandardEntity.Id(organisationId, "IFRS"), "IFRS Label", true);
        when(reportStandardRepository.findById(new ReportStandardEntity.Id(organisationId, "IFRS"))).thenReturn(Optional.of(existing));

        ReportStandardResponseDto result = reportStandardService.insertReportStandard(organisationId, update, false);

        assertTrue(result.getError().isPresent());
        assertEquals("REPORT_STANDARD_ALREADY_EXISTS", result.getError().get().getTitle());
        assertEquals("Standard with code IFRS already exists", result.getError().get().getDetail());
        verify(reportStandardRepository, never()).save(any());
    }

    @Test
    void insertReportStandard_upsert_existing() {
        ReportStandardDto update = new ReportStandardDto("IFRS", "Updated Label", false);
        ReportStandardEntity existing = new ReportStandardEntity(new ReportStandardEntity.Id(organisationId, "IFRS"), "Old Label", true);
        when(reportStandardRepository.findById(new ReportStandardEntity.Id(organisationId, "IFRS"))).thenReturn(Optional.of(existing));
        when(reportStandardRepository.save(existing)).thenReturn(existing);

        ReportStandardResponseDto result = reportStandardService.insertReportStandard(organisationId, update, true);

        assertTrue(result.getError().isEmpty());
        assertEquals("Updated Label", result.getLabel());
        assertFalse(result.isActive());
    }

    @Test
    void insertReportStandard_success_new() {
        ReportStandardDto update = new ReportStandardDto("IFRS", "IFRS Label", true);
        when(reportStandardRepository.findById(new ReportStandardEntity.Id(organisationId, "IFRS"))).thenReturn(Optional.empty());

        ArgumentCaptor<ReportStandardEntity> captor = ArgumentCaptor.forClass(ReportStandardEntity.class);
        when(reportStandardRepository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        ReportStandardResponseDto result = reportStandardService.insertReportStandard(organisationId, update, false);

        assertTrue(result.getError().isEmpty());
        assertEquals("IFRS", result.getCode());
        assertEquals("IFRS Label", result.getLabel());
        assertEquals("IFRS", captor.getValue().getId().getCode());
        assertEquals(organisationId, captor.getValue().getId().getOrganisationId());
    }

    @Test
    void insertViaCsv_parseError() {
        MultipartFile file = mock(MultipartFile.class);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Parse Error");
        when(csvParser.parseCsv(file, ReportStandardDto.class)).thenReturn(Either.left(problem));

        Either<ProblemDetail, List<ReportStandardResponseDto>> result = reportStandardService.insertViaCsv(organisationId, file);

        assertTrue(result.isLeft());
        assertEquals("Parse Error", result.getLeft().getDetail());
    }

    @Test
    void insertViaCsv_validationError() {
        MultipartFile file = mock(MultipartFile.class);
        ReportStandardDto update = new ReportStandardDto(null, "IFRS Label", true);
        Errors errors = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);
        when(objectError.getDefaultMessage()).thenReturn("Code is required");
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(validator.validateObject(update)).thenReturn(errors);
        when(csvParser.parseCsv(file, ReportStandardDto.class)).thenReturn(Either.right(List.of(update)));

        Either<ProblemDetail, List<ReportStandardResponseDto>> result = reportStandardService.insertViaCsv(organisationId, file);

        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
        assertTrue(result.get().get(0).getError().isPresent());
        assertEquals("VALIDATION_ERROR", result.get().get(0).getError().get().getTitle());
        assertEquals("Code is required", result.get().get(0).getError().get().getDetail());
        verify(reportStandardRepository, never()).save(any());
    }

    @Test
    void insertViaCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        ReportStandardDto update = new ReportStandardDto("IFRS", "IFRS Label", true);
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(update)).thenReturn(errors);
        when(csvParser.parseCsv(file, ReportStandardDto.class)).thenReturn(Either.right(List.of(update)));
        when(reportStandardRepository.findById(new ReportStandardEntity.Id(organisationId, "IFRS"))).thenReturn(Optional.empty());
        when(reportStandardRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Either<ProblemDetail, List<ReportStandardResponseDto>> result = reportStandardService.insertViaCsv(organisationId, file);

        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
        assertTrue(result.get().get(0).getError().isEmpty());
        assertEquals("IFRS", result.get().get(0).getCode());
    }

}
