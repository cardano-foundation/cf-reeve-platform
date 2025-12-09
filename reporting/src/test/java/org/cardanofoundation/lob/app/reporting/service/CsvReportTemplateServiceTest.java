package org.cardanofoundation.lob.app.reporting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountSubType;
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountType;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountTypeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.reporting.dto.CreateCsvTemplateRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.dto.TemplateCsvLine;
import org.cardanofoundation.lob.app.reporting.mapper.ReportTemplateMapper;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;

@ExtendWith(MockitoExtension.class)
class CsvReportTemplateServiceTest {

    @Mock
    private OrganisationPublicApiIF organisationPublicApi;
    @Mock
    private CsvParser<TemplateCsvLine> csvParser;
    @Mock
    private ReportTemplateRepository reportTemplateRepository;
    @Mock
    private ReportTemplateMapper reportTemplateMapper;
    @Mock
    private ChartOfAccountTypeRepository chartOfAccountTypeRepository;
    @Mock
    private Validator validator;
    @InjectMocks
    private CsvReportTemplateService reportTemplateService;

    @Test
    void createCsvTemplates_orgNotFound() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.empty());
        when(request.getOrganisationId()).thenReturn("org123");

        Either<Problem, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isLeft());
        assertEquals("ORGANISATION_NOT_FOUND", result.getLeft().getTitle());
    }

    @Test
    void createCsvTemplates_csvParseError() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.left(Problem.builder().withTitle("CSV_PARSE_ERROR").build()));
        when(request.getFile()).thenReturn(file);

        Either<Problem, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isLeft());
        assertEquals("CSV_PARSE_ERROR", result.getLeft().getTitle());
    }

    @Test
    void createCsvTemplates_validationError() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);
        TemplateCsvLine templateCsvLine = mock(TemplateCsvLine.class);
        Errors errors = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);
        when(objectError.getDefaultMessage()).thenReturn("Default Message");
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(templateCsvLine)));
        when(request.getFile()).thenReturn(file);
        when(validator.validateObject(templateCsvLine)).thenReturn(errors);

        Either<Problem, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isLeft());
        assertEquals("CSV_PARSING_ERROR", result.getLeft().getTitle());
    }

    @Test
    void createCsvTemplates_wrongReportType() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);
        TemplateCsvLine templateCsvLine = mock(TemplateCsvLine.class);
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(templateCsvLine)));
        when(request.getFile()).thenReturn(file);
        when(validator.validateObject(templateCsvLine)).thenReturn(errors);
        when(templateCsvLine.getName()).thenReturn("Test Template");
        when(templateCsvLine.getReportType()).thenReturn("WRONG_TYPE");

        Either<Problem, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        assertTrue(responseDtos.getFirst().getError().isPresent());
        assertEquals("CSV_PARSING_ERROR", responseDtos.getFirst().getError().get().getTitle());
        assertEquals("Invalid report template type: WRONG_TYPE. Options are: BALANCE_SHEET, INCOME_STATEMENT, CUSTOM", responseDtos.getFirst().getError().get().getDetail());
    }

    @Test
    void createCsvTemplates_wrongTypeMapping() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);
        TemplateCsvLine templateCsvLine = mock(TemplateCsvLine.class);
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(templateCsvLine)));
        when(request.getFile()).thenReturn(file);
        when(validator.validateObject(templateCsvLine)).thenReturn(errors);
        when(templateCsvLine.getName()).thenReturn("Test Template");
        when(templateCsvLine.getReportType()).thenReturn("BALANCE_SHEET");
        when(templateCsvLine.getDataMode()).thenReturn("USER");
        when(templateCsvLine.getTypes()).thenReturn("InvalidMapping");
        Either<Problem, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        assertTrue(responseDtos.getFirst().getError().isPresent());
        assertEquals("CSV_PARSING_ERROR", responseDtos.getFirst().getError().get().getTitle());
        assertEquals("Invalid chart of account mapping: InvalidMapping. Expected format 'TYPE_SUBTYPE' and semicolon seperated.", responseDtos.getFirst().getError().get().getDetail());
    }

    @Test
    void createCsvTemplates_typeNotFound() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);
        TemplateCsvLine templateCsvLine = mock(TemplateCsvLine.class);
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(templateCsvLine)));
        when(request.getFile()).thenReturn(file);
        when(validator.validateObject(templateCsvLine)).thenReturn(errors);
        when(templateCsvLine.getName()).thenReturn("Test Template");
        when(templateCsvLine.getReportType()).thenReturn("BALANCE_SHEET");
        when(templateCsvLine.getDataMode()).thenReturn("USER");
        when(templateCsvLine.getTypes()).thenReturn("type_subtype");
        when(chartOfAccountTypeRepository.findFirstByOrganisationIdAndName("org123", "type")).thenReturn(Optional.empty());
        Either<Problem, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        assertTrue(responseDtos.getFirst().getError().isPresent());
        assertEquals("CSV_PARSING_ERROR", responseDtos.getFirst().getError().get().getTitle());
        assertEquals("Chart of account type not found: type", responseDtos.getFirst().getError().get().getDetail());
    }

    @Test
    void createCsvTemplates_subTypeNotFound() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);
        TemplateCsvLine templateCsvLine = mock(TemplateCsvLine.class);
        Errors errors = mock(Errors.class);
        ChartOfAccountType chartOfAccountType = mock(ChartOfAccountType.class);

        when(errors.getAllErrors()).thenReturn(List.of());
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(templateCsvLine)));
        when(request.getFile()).thenReturn(file);
        when(validator.validateObject(templateCsvLine)).thenReturn(errors);
        when(templateCsvLine.getName()).thenReturn("Test Template");
        when(templateCsvLine.getReportType()).thenReturn("BALANCE_SHEET");
        when(templateCsvLine.getDataMode()).thenReturn("USER");
        when(templateCsvLine.getTypes()).thenReturn("type_subtype");
        when(chartOfAccountTypeRepository.findFirstByOrganisationIdAndName("org123", "type")).thenReturn(Optional.of(chartOfAccountType));
        when(chartOfAccountType.getSubTypes()).thenReturn(Set.of());
        Either<Problem, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        assertTrue(responseDtos.getFirst().getError().isPresent());
        assertEquals("CSV_PARSING_ERROR", responseDtos.getFirst().getError().get().getTitle());
        assertEquals("Chart of account subtype not found: subtype for type: type", responseDtos.getFirst().getError().get().getDetail());
    }

    @Test
    void createCsvTemplates_parentNotFound() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);
        TemplateCsvLine templateCsvLine = mock(TemplateCsvLine.class);
        Errors errors = mock(Errors.class);
        ChartOfAccountType chartOfAccountType = mock(ChartOfAccountType.class);
        ChartOfAccountSubType chartOfAccountSubType = mock(ChartOfAccountSubType.class);
        ReportTemplateEntity saved = mock(ReportTemplateEntity.class);
        ReportTemplateResponseDto responseDto = mock(ReportTemplateResponseDto.class);

        when(errors.getAllErrors()).thenReturn(List.of());
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(templateCsvLine)));
        when(request.getFile()).thenReturn(file);
        when(validator.validateObject(templateCsvLine)).thenReturn(errors);
        when(templateCsvLine.getName()).thenReturn("Test Template");
        when(templateCsvLine.getReportType()).thenReturn("BALANCE_SHEET");
        when(templateCsvLine.getDataMode()).thenReturn("USER");
        when(templateCsvLine.getTypes()).thenReturn("type_subtype");
        when(chartOfAccountTypeRepository.findFirstByOrganisationIdAndName("org123", "type")).thenReturn(Optional.of(chartOfAccountType));
        when(chartOfAccountType.getSubTypes()).thenReturn(Set.of(chartOfAccountSubType));
        when(chartOfAccountSubType.getName()).thenReturn("subtype");
        when(chartOfAccountSubType.getId()).thenReturn(1L);
        when(templateCsvLine.getParent()).thenReturn("Parent");

        Either<Problem, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        ReportTemplateResponseDto first = responseDtos.getFirst();
        assertTrue(first.getError().isPresent());
        assertEquals("CSV_PARSING_ERROR", responseDtos.getFirst().getError().get().getTitle());
        assertEquals("Parent field not found: Parent for field: null. Note: The parent field must be defined before the child field in the CSV.", responseDtos.getFirst().getError().get().getDetail());
    }

    @Test
    void createCsvTemplates_success() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);
        TemplateCsvLine templateCsvLine = mock(TemplateCsvLine.class);
        Errors errors = mock(Errors.class);
        ChartOfAccountType chartOfAccountType = mock(ChartOfAccountType.class);
        ChartOfAccountSubType chartOfAccountSubType = mock(ChartOfAccountSubType.class);
        ReportTemplateResponseDto responseDto = mock(ReportTemplateResponseDto.class);

        when(errors.getAllErrors()).thenReturn(List.of());
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(templateCsvLine)));
        when(request.getFile()).thenReturn(file);
        when(validator.validateObject(templateCsvLine)).thenReturn(errors);
        when(templateCsvLine.getName()).thenReturn("Test Template");
        when(templateCsvLine.getReportType()).thenReturn("BALANCE_SHEET");
        when(templateCsvLine.getDataMode()).thenReturn("USER");
        when(templateCsvLine.getTypes()).thenReturn("type_subtype");
        when(chartOfAccountTypeRepository.findFirstByOrganisationIdAndName("org123", "type")).thenReturn(Optional.of(chartOfAccountType));
        when(chartOfAccountType.getSubTypes()).thenReturn(Set.of(chartOfAccountSubType));
        when(chartOfAccountSubType.getName()).thenReturn("subtype");
        when(chartOfAccountSubType.getId()).thenReturn(1L);
        when(templateCsvLine.getParent()).thenReturn("");
        when(reportTemplateMapper.toResponseDto(any())).thenReturn(responseDto);

        Either<Problem, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        ReportTemplateResponseDto first = responseDtos.getFirst();
        assertTrue(first.getError().isEmpty());
    }
}
