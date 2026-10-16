package org.cardanofoundation.lob.app.reporting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
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
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.AccountingRegime;
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccount;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.repository.AccountingRegimeRepository;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.reporting.dto.CreateCsvTemplateRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.dto.TemplateCsvLine;
import org.cardanofoundation.lob.app.reporting.mapper.ReportTemplateMapper;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;
import org.cardanofoundation.lob.app.reporting.typeValidations.ReportTemplateTypeValidator;

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
    private ChartOfAccountRepository chartOfAccountRepository;
    @Mock
    private Validator validator;
    @Mock
    private ReportTemplateService reportTemplateServiceDependency;
    @Mock
    private ReportingRepository reportingRepository;
    @Mock
    private AccountingRegimeRepository accountingRegimeRepository;
    private AccountingRegimeResolver accountingRegimeResolver;
    private CsvReportTemplateService reportTemplateService;

    @BeforeEach
    void setUpAccountingRegimes() {
        lenient().when(accountingRegimeRepository.findAllByOrganisationId("org123")).thenReturn(Set.of(
                new AccountingRegime(new AccountingRegime.Id("org123", "IFRS"), "IFRS", true),
                new AccountingRegime(new AccountingRegime.Id("org123", "GAAP"), "GAAP", true),
                new AccountingRegime(new AccountingRegime.Id("org123", "OLD"), "Old Regime", false)));
        accountingRegimeResolver = new AccountingRegimeResolver(accountingRegimeRepository);
        // The regime rules live in ReportTemplateService; run the real ones unless a test stubs them
        ReportTemplateService regimeRules = new ReportTemplateService(reportTemplateRepository, reportTemplateMapper, chartOfAccountRepository,
                reportingRepository, validator, mock(ReportTemplateTypeValidator.class), mock(ReportingService.class), accountingRegimeResolver);
        lenient().when(reportTemplateServiceDependency.validateAccountingRegime(any(), any()))
                .thenAnswer(invocation -> regimeRules.validateAccountingRegime(invocation.getArgument(0), invocation.getArgument(1)));
        reportTemplateService = new CsvReportTemplateService(organisationPublicApi, csvParser, reportTemplateRepository, reportingRepository,
                reportTemplateMapper, chartOfAccountRepository, validator, reportTemplateServiceDependency);
    }

    @Test
    void createCsvTemplates_orgNotFound() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.empty());
        when(request.getOrganisationId()).thenReturn("org123");

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

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
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.left(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "CSV_PARSE_ERROR")));
        when(request.getFile()).thenReturn(file);

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isLeft());
        assertEquals("CSV_PARSE_ERROR", result.getLeft().getDetail());
    }

    @Test
    void createCsvTemplates_noContent() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);

        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of()));
        when(request.getFile()).thenReturn(file);

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isLeft());
        assertEquals("CSV_PARSING_ERROR", result.getLeft().getTitle());
        assertEquals("CSV file has no content lines.", result.getLeft().getDetail());
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

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isLeft());
        assertEquals("CSV_PARSING_ERROR", result.getLeft().getTitle());
    }

    @Test
    void createCsvTemplates_missingAccountingRegime_realValidatorRejects() {
        LocalValidatorFactoryBean realValidator = new LocalValidatorFactoryBean();
        realValidator.afterPropertiesSet();

        CsvReportTemplateService serviceWithRealValidator = new CsvReportTemplateService(
                organisationPublicApi,
                csvParser,
                reportTemplateRepository,
                null,
                reportTemplateMapper,
                chartOfAccountRepository,
                realValidator,
                reportTemplateServiceDependency);

        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);
        TemplateCsvLine templateCsvLine = new TemplateCsvLine();
        templateCsvLine.setName("Test Template");
        templateCsvLine.setReportType("Balance sheet");
        templateCsvLine.setDataMode("Manual");
        templateCsvLine.setActive("true");
        templateCsvLine.setFieldName("Revenue");
        templateCsvLine.setAccountingRegime(null);
        templateCsvLine.setSign("Positive");

        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(templateCsvLine)));
        when(request.getFile()).thenReturn(file);

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = serviceWithRealValidator.createCsvTemplates(request);

        assertTrue(result.isLeft());
        assertEquals("CSV_PARSING_ERROR", result.getLeft().getTitle());
        assertEquals("Row 1: Accounting Regime is required", result.getLeft().getDetail());
    }

    @Test
    void createCsvTemplates_missingSign_errorPointsToFailingRow() {
        LocalValidatorFactoryBean realValidator = new LocalValidatorFactoryBean();
        realValidator.afterPropertiesSet();

        CsvReportTemplateService serviceWithRealValidator = new CsvReportTemplateService(
                organisationPublicApi,
                csvParser,
                reportTemplateRepository,
                null,
                reportTemplateMapper,
                chartOfAccountRepository,
                realValidator,
                reportTemplateServiceDependency);

        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        MultipartFile file = mock(MultipartFile.class);
        TemplateCsvLine revenue = new TemplateCsvLine("Test Template", "Balance sheet", "Automatic", "IFRS", "true", "", "Revenue", "", "", "End-of-Period balance", "Positive");
        TemplateCsvLine costs = new TemplateCsvLine("Test Template", "Balance sheet", "Automatic", "IFRS", "true", "", "Costs", "", "", "End-of-Period balance", "Negative");
        TemplateCsvLine profit = new TemplateCsvLine("Test Template", "Balance sheet", "Automatic", "IFRS", "true", "", "Profit", "", "", "End-of-Period balance", null);

        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(new Organisation()));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(revenue, costs, profit)));
        when(request.getFile()).thenReturn(file);

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = serviceWithRealValidator.createCsvTemplates(request);

        assertTrue(result.isRight());
        ProblemDetail error = result.get().getFirst().getError().orElseThrow();
        assertEquals("CSV_PARSING_ERROR", error.getTitle());
        assertEquals("Row 3: Sign is required for Automatic templates. Options are: Positive, Negative", error.getDetail());
        assertEquals(3, error.getProperties().get("rowNumber"));
    }

    @Test
    void createCsvTemplates_automaticMissingDateRange_isRejected() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        MultipartFile file = mock(MultipartFile.class);
        Errors errors = mock(Errors.class);
        TemplateCsvLine line = new TemplateCsvLine("Test Template", "Balance sheet", "Automatic", "IFRS", "true", "", "Revenue", "", "", "", "Positive");

        when(errors.getAllErrors()).thenReturn(List.of());
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(new Organisation()));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(request.getFile()).thenReturn(file);
        when(validator.validateObject(line)).thenReturn(errors);

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        assertEquals("Row 1: Date Range is required for Automatic templates. Options are: Period-Only balance, End-of-Period balance, Year-to-Date balance, Previous-Year balance, Previous-Year-to-Date balance, End-of-Previous-Year balance",
                result.get().getFirst().getError().orElseThrow().getDetail());
    }

    @Test
    void createCsvTemplates_manualWithoutDateRangeAndSign_isAccepted() {
        LocalValidatorFactoryBean realValidator = new LocalValidatorFactoryBean();
        realValidator.afterPropertiesSet();
        CsvReportTemplateService serviceWithRealValidator = new CsvReportTemplateService(
                organisationPublicApi,
                csvParser,
                reportTemplateRepository,
                null,
                reportTemplateMapper,
                chartOfAccountRepository,
                realValidator,
                reportTemplateServiceDependency);

        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        MultipartFile file = mock(MultipartFile.class);
        // Sign column left blank on one row and omitted entirely (null) on the other; Date Range blank on both
        TemplateCsvLine revenue = new TemplateCsvLine("Test Template", "Balance sheet", "Manual", "IFRS", "true", "", "Revenue", "", "", "", "");
        TemplateCsvLine costs = new TemplateCsvLine("Test Template", "Balance sheet", "Manual", "IFRS", "true", "", "Costs", "", "", null, null);

        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(new Organisation()));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(revenue, costs)));
        when(request.getFile()).thenReturn(file);
        when(reportTemplateServiceDependency.validateDataMode(any(ReportTemplateDto.class))).thenReturn(Either.right(null));
        ArgumentCaptor<ReportTemplateDto> dtoCaptor = ArgumentCaptor.forClass(ReportTemplateDto.class);
        when(reportTemplateMapper.toEntity(dtoCaptor.capture(), isNull())).thenReturn(mock(ReportTemplateEntity.class));
        when(reportTemplateMapper.toResponseDto(any())).thenReturn(mock(ReportTemplateResponseDto.class));

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = serviceWithRealValidator.createCsvTemplates(request);

        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
        List<ReportTemplateFieldDto> fields = dtoCaptor.getValue().getFields();
        assertEquals(2, fields.size());
        // Left unset, so the mapper applies the same defaults as the template builder
        assertNull(fields.getFirst().getDateRange());
        assertFalse(fields.getFirst().isNegated());
        assertNull(fields.get(1).getDateRange());
        assertFalse(fields.get(1).isNegated());
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

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        assertTrue(responseDtos.getFirst().getError().isPresent());
        assertEquals("CSV_PARSING_ERROR", responseDtos.getFirst().getError().get().getTitle());
        assertEquals("Row 1: Invalid report type: WRONG_TYPE. Options are: Balance sheet, Income statement, Custom", responseDtos.getFirst().getError().get().getDetail());
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
        when(templateCsvLine.getReportType()).thenReturn("Balance sheet");
        when(templateCsvLine.getDataMode()).thenReturn("Manual");
        when(templateCsvLine.getActive()).thenReturn("true");
        when(templateCsvLine.getAccountingRegime()).thenReturn("IFRS");
        when(templateCsvLine.getAccounts()).thenReturn("InvalidMapping");
        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        assertTrue(responseDtos.getFirst().getError().isPresent());
        assertEquals("CSV_PARSING_ERROR", responseDtos.getFirst().getError().get().getTitle());
        assertEquals("Row 1: Chart of account not found: InvalidMapping", responseDtos.getFirst().getError().get().getDetail());
    }

    @Test
    void createCsvTemplates_wrongDateRangeMapping() {
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
        when(templateCsvLine.getReportType()).thenReturn("Balance sheet");
        when(templateCsvLine.getDataMode()).thenReturn("Automatic");
        when(templateCsvLine.getActive()).thenReturn("true");
        when(templateCsvLine.getAccountingRegime()).thenReturn("IFRS");
        when(templateCsvLine.getDateRange()).thenReturn("InvalidMapping");
        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        assertTrue(responseDtos.getFirst().getError().isPresent());
        assertEquals("CSV_PARSING_ERROR", responseDtos.getFirst().getError().get().getTitle());
        assertEquals("Row 1: Invalid date range: InvalidMapping. Options are: Period-Only balance, End-of-Period balance, Year-to-Date balance, Previous-Year balance, Previous-Year-to-Date balance, End-of-Previous-Year balance", responseDtos.getFirst().getError().get().getDetail());
    }

    @Test
    void createCsvTemplates_wrongSignMapping() {
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
        when(templateCsvLine.getReportType()).thenReturn("Balance sheet");
        when(templateCsvLine.getDataMode()).thenReturn("Automatic");
        when(templateCsvLine.getActive()).thenReturn("true");
        when(templateCsvLine.getAccountingRegime()).thenReturn("IFRS");
        when(templateCsvLine.getDateRange()).thenReturn("Period-Only balance");
        when(templateCsvLine.getSign()).thenReturn("Neutral");
        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        assertTrue(responseDtos.getFirst().getError().isPresent());
        assertEquals("CSV_PARSING_ERROR", responseDtos.getFirst().getError().get().getTitle());
        assertEquals("Row 1: Invalid Sign value: Neutral. Options are: Positive, Negative", responseDtos.getFirst().getError().get().getDetail());
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
        when(templateCsvLine.getReportType()).thenReturn("Balance sheet");
        when(templateCsvLine.getDataMode()).thenReturn("Manual");
        when(templateCsvLine.getActive()).thenReturn("true");
        when(templateCsvLine.getAccountingRegime()).thenReturn("IFRS");
        when(templateCsvLine.getAccounts()).thenReturn("1233");
        when(chartOfAccountRepository.findById(any(ChartOfAccount.Id.class))).thenReturn(Optional.empty());
        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        assertTrue(responseDtos.getFirst().getError().isPresent());
        assertEquals("CSV_PARSING_ERROR", responseDtos.getFirst().getError().get().getTitle());
        assertEquals("Row 1: Chart of account not found: 1233", responseDtos.getFirst().getError().get().getDetail());
    }

    @Test
    void createCsvTemplates_parentNotFound() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);
        TemplateCsvLine templateCsvLine = mock(TemplateCsvLine.class);
        Errors errors = mock(Errors.class);
        ChartOfAccount chartOfAccount = mock(ChartOfAccount.class);

        when(chartOfAccount.getId()).thenReturn(new ChartOfAccount.Id("org123", "1234"));
        when(errors.getAllErrors()).thenReturn(List.of());
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(templateCsvLine)));
        when(request.getFile()).thenReturn(file);
        when(validator.validateObject(templateCsvLine)).thenReturn(errors);
        when(templateCsvLine.getName()).thenReturn("Test Template");
        when(templateCsvLine.getReportType()).thenReturn("Balance sheet");
        when(templateCsvLine.getDataMode()).thenReturn("Manual");
        when(templateCsvLine.getActive()).thenReturn("true");
        when(templateCsvLine.getAccountingRegime()).thenReturn("IFRS");
        when(templateCsvLine.getAccounts()).thenReturn("1234");
        when(chartOfAccountRepository.findById(any(ChartOfAccount.Id.class))).thenReturn(Optional.of(chartOfAccount));
        when(templateCsvLine.getParent()).thenReturn("Parent");

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        ReportTemplateResponseDto first = responseDtos.getFirst();
        assertTrue(first.getError().isPresent());
        assertEquals("CSV_PARSING_ERROR", responseDtos.getFirst().getError().get().getTitle());
        assertEquals("Row 1: Parent field not found: Parent for field: null. The Parent value must exactly match the Field Name of a row that appears earlier in the CSV for the same template.", responseDtos.getFirst().getError().get().getDetail());
    }

    @Test
    void createCsvTemplates_success() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);
        TemplateCsvLine templateCsvLine = mock(TemplateCsvLine.class);
        Errors errors = mock(Errors.class);
        ChartOfAccount chartOfAccount = mock(ChartOfAccount.class);
        ReportTemplateResponseDto responseDto = mock(ReportTemplateResponseDto.class);

        when(errors.getAllErrors()).thenReturn(List.of());
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(templateCsvLine)));
        when(request.getFile()).thenReturn(file);
        when(validator.validateObject(templateCsvLine)).thenReturn(errors);
        when(templateCsvLine.getName()).thenReturn("Test Template");
        when(templateCsvLine.getReportType()).thenReturn("Balance sheet");
        when(templateCsvLine.getDataMode()).thenReturn("Manual");
        when(templateCsvLine.getActive()).thenReturn("true");
        when(templateCsvLine.getAccountingRegime()).thenReturn("IFRS");
        when(templateCsvLine.getAccounts()).thenReturn("1234");
        when(chartOfAccountRepository.findById(new ChartOfAccount.Id("org123", "1234"))).thenReturn(Optional.of(chartOfAccount));
        when(templateCsvLine.getParent()).thenReturn("");
        when(chartOfAccount.getId()).thenReturn(new ChartOfAccount.Id("org123", "1234"));
        when(reportTemplateMapper.toResponseDto(any())).thenReturn(responseDto);
        when(reportTemplateServiceDependency.validateDataMode(any(ReportTemplateDto.class))).thenReturn(Either.right(null));

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        ReportTemplateResponseDto first = responseDtos.getFirst();
        assertTrue(first.getError().isEmpty());
    }

    @Test
    void createCsvTemplates_negativeSignMapsToNegatedField() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);
        TemplateCsvLine templateCsvLine = mock(TemplateCsvLine.class);
        Errors errors = mock(Errors.class);
        ChartOfAccount chartOfAccount = mock(ChartOfAccount.class);

        when(errors.getAllErrors()).thenReturn(List.of());
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(templateCsvLine)));
        when(request.getFile()).thenReturn(file);
        when(validator.validateObject(templateCsvLine)).thenReturn(errors);
        when(templateCsvLine.getName()).thenReturn("Test Template");
        when(templateCsvLine.getReportType()).thenReturn("Balance sheet");
        when(templateCsvLine.getDataMode()).thenReturn("Automatic");
        when(templateCsvLine.getActive()).thenReturn("true");
        when(templateCsvLine.getAccountingRegime()).thenReturn("IFRS");
        when(templateCsvLine.getAccounts()).thenReturn("1234");
        when(templateCsvLine.getDateRange()).thenReturn("Period-Only balance");
        when(templateCsvLine.getSign()).thenReturn("Negative");
        when(chartOfAccountRepository.findById(new ChartOfAccount.Id("org123", "1234"))).thenReturn(Optional.of(chartOfAccount));
        when(templateCsvLine.getParent()).thenReturn("");
        when(chartOfAccount.getId()).thenReturn(new ChartOfAccount.Id("org123", "1234"));

        ArgumentCaptor<ReportTemplateDto> dtoCaptor = ArgumentCaptor.forClass(ReportTemplateDto.class);
        when(reportTemplateMapper.toEntity(dtoCaptor.capture(), isNull())).thenReturn(mock(ReportTemplateEntity.class));
        when(reportTemplateMapper.toResponseDto(any())).thenReturn(mock(ReportTemplateResponseDto.class));
        when(reportTemplateServiceDependency.validateDataMode(any(ReportTemplateDto.class))).thenReturn(Either.right(null));

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        assertTrue(dtoCaptor.getValue().getFields().getFirst().isNegated());
    }

    @Test
    void createCsvTemplates_success_accountingRegimePropagatedFromCsv() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);
        TemplateCsvLine templateCsvLine = mock(TemplateCsvLine.class);
        Errors errors = mock(Errors.class);
        ChartOfAccount chartOfAccount = mock(ChartOfAccount.class);
        ReportTemplateResponseDto responseDto = mock(ReportTemplateResponseDto.class);

        when(errors.getAllErrors()).thenReturn(List.of());
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(templateCsvLine)));
        when(request.getFile()).thenReturn(file);
        when(validator.validateObject(templateCsvLine)).thenReturn(errors);
        when(templateCsvLine.getName()).thenReturn("Test Template");
        when(templateCsvLine.getReportType()).thenReturn("Balance sheet");
        when(templateCsvLine.getDataMode()).thenReturn("Manual");
        when(templateCsvLine.getActive()).thenReturn("true");
        when(templateCsvLine.getAccounts()).thenReturn("1234");
        when(templateCsvLine.getAccountingRegime()).thenReturn("IFRS");
        when(chartOfAccountRepository.findById(new ChartOfAccount.Id("org123", "1234"))).thenReturn(Optional.of(chartOfAccount));
        when(templateCsvLine.getParent()).thenReturn("");
        when(chartOfAccount.getId()).thenReturn(new ChartOfAccount.Id("org123", "1234"));

        ArgumentCaptor<ReportTemplateDto> dtoCaptor = ArgumentCaptor.forClass(ReportTemplateDto.class);
        when(reportTemplateMapper.toEntity(dtoCaptor.capture(), isNull())).thenReturn(mock(ReportTemplateEntity.class));
        when(reportTemplateMapper.toResponseDto(any())).thenReturn(responseDto);
        when(reportTemplateServiceDependency.validateDataMode(any(ReportTemplateDto.class))).thenReturn(Either.right(null));

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        assertEquals("IFRS", dtoCaptor.getValue().getAccountingRegime());
    }

    @Test
    void createCsvTemplates_accountingRegimeChangeBlockedAfterPublish() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);
        TemplateCsvLine templateCsvLine = mock(TemplateCsvLine.class);
        Errors errors = mock(Errors.class);
        ChartOfAccount chartOfAccount = mock(ChartOfAccount.class);
        ReportTemplateEntity existingTemplate = mock(ReportTemplateEntity.class);
        ProblemDetail immutableProblem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The accounting regime of template 'Test Template' cannot be changed because it has at least one published report.");
        immutableProblem.setTitle("ACCOUNTING_REGIME_IMMUTABLE_AFTER_PUBLISH");

        when(errors.getAllErrors()).thenReturn(List.of());
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(templateCsvLine)));
        when(request.getFile()).thenReturn(file);
        when(validator.validateObject(templateCsvLine)).thenReturn(errors);
        when(templateCsvLine.getName()).thenReturn("Test Template");
        when(templateCsvLine.getReportType()).thenReturn("Balance sheet");
        when(templateCsvLine.getDataMode()).thenReturn("Manual");
        when(templateCsvLine.getActive()).thenReturn("true");
        when(templateCsvLine.getAccounts()).thenReturn("1234");
        when(templateCsvLine.getAccountingRegime()).thenReturn("GAAP");
        when(chartOfAccountRepository.findById(new ChartOfAccount.Id("org123", "1234"))).thenReturn(Optional.of(chartOfAccount));
        when(templateCsvLine.getParent()).thenReturn("");
        when(chartOfAccount.getId()).thenReturn(new ChartOfAccount.Id("org123", "1234"));
        when(reportTemplateRepository.findByOrgnisationIdAndNameAndReportTemplateTypeLatestVersion("org123", "Test Template", ReportTemplateType.BALANCE_SHEET))
                .thenReturn(Optional.of(existingTemplate));
        // doReturn: when(...) would run the default answer that delegates to the real rules
        doReturn(Either.left(immutableProblem)).when(reportTemplateServiceDependency)
                .validateAccountingRegime(any(ReportTemplateDto.class), eq(Optional.of(existingTemplate)));
        when(reportTemplateServiceDependency.validateDataMode(any(ReportTemplateDto.class))).thenReturn(Either.right(null));

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        ReportTemplateResponseDto first = responseDtos.getFirst();
        assertTrue(first.getError().isPresent());
        assertEquals("ACCOUNTING_REGIME_IMMUTABLE_AFTER_PUBLISH", first.getError().get().getTitle());
        verify(reportTemplateRepository, never()).saveAndFlush(any());
    }

    @Test
    void createCsvTemplates_childFieldsAreNotDuplicatedAtTopLevel() {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);
        ChartOfAccount chartOfAccount = mock(ChartOfAccount.class);
        ReportTemplateResponseDto responseDto = mock(ReportTemplateResponseDto.class);

        TemplateCsvLine revenueLine = mock(TemplateCsvLine.class);
        TemplateCsvLine assetsLine = mock(TemplateCsvLine.class);
        TemplateCsvLine testLine = mock(TemplateCsvLine.class);

        for (TemplateCsvLine line : List.of(revenueLine, assetsLine, testLine)) {
            when(line.getReportType()).thenReturn("Balance sheet");
            when(line.getAccounts()).thenReturn("1234");
        }
        when(revenueLine.getDataMode()).thenReturn("Manual");
        when(revenueLine.getActive()).thenReturn("true");
        when(revenueLine.getAccountingRegime()).thenReturn("IFRS");
        when(revenueLine.getFieldName()).thenReturn("Revenue");
        when(revenueLine.getParent()).thenReturn("");
        when(assetsLine.getFieldName()).thenReturn("Assets");
        when(assetsLine.getParent()).thenReturn("Revenue");
        when(testLine.getFieldName()).thenReturn("Test");
        when(testLine.getParent()).thenReturn("Revenue");

        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any(TemplateCsvLine.class))).thenReturn(errors);

        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(request.getFile()).thenReturn(file);
        when(csvParser.parseCsv(file, TemplateCsvLine.class))
                .thenReturn(Either.right(List.of(revenueLine, assetsLine, testLine)));
        when(revenueLine.getName()).thenReturn("Test Template");
        when(assetsLine.getName()).thenReturn("Test Template");
        when(testLine.getName()).thenReturn("Test Template");
        when(chartOfAccountRepository.findById(new ChartOfAccount.Id("org123", "1234"))).thenReturn(Optional.of(chartOfAccount));
        when(chartOfAccount.getId()).thenReturn(new ChartOfAccount.Id("org123", "1234"));
        when(reportTemplateMapper.toEntity(any(ReportTemplateDto.class), any())).thenReturn(mock(ReportTemplateEntity.class));
        when(reportTemplateMapper.toResponseDto(any())).thenReturn(responseDto);
        when(reportTemplateServiceDependency.validateDataMode(any(ReportTemplateDto.class))).thenReturn(Either.right(null));

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        assertTrue(responseDtos.getFirst().getError().isEmpty());

        ArgumentCaptor<ReportTemplateDto> dtoCaptor = ArgumentCaptor.forClass(ReportTemplateDto.class);
        verify(reportTemplateMapper).toEntity(dtoCaptor.capture(), any());
        List<ReportTemplateFieldDto> topLevelFields = dtoCaptor.getValue().getFields();

        assertEquals(1, topLevelFields.size());
        ReportTemplateFieldDto revenueField = topLevelFields.getFirst();
        assertEquals("Revenue", revenueField.getFieldName());
        assertEquals(2, revenueField.getChildFields().size());
        assertEquals("Assets", revenueField.getChildFields().get(0).getFieldName());
        assertEquals("Test", revenueField.getChildFields().get(1).getFieldName());
    }

    @Test
    void createCsvTemplates_threeLevelNesting() {
        List<ReportTemplateFieldDto> topLevelFields = importFieldTree(List.of(
                new String[]{"Assets", ""},
                new String[]{"Non Current Assets", "Assets"},
                new String[]{"Tangible Assets", "Non Current Assets"}));

        assertEquals(1, topLevelFields.size());
        ReportTemplateFieldDto assets = topLevelFields.getFirst();
        assertEquals("Assets", assets.getFieldName());
        assertEquals(1, assets.getChildFields().size());
        ReportTemplateFieldDto nonCurrentAssets = assets.getChildFields().getFirst();
        assertEquals("Non Current Assets", nonCurrentAssets.getFieldName());
        assertEquals(1, nonCurrentAssets.getChildFields().size());
        assertEquals("Tangible Assets", nonCurrentAssets.getChildFields().getFirst().getFieldName());
    }

    @Test
    void createCsvTemplates_sameNameUnderDifferentParents_childAttachesToClosestPrecedingParent() {
        List<ReportTemplateFieldDto> topLevelFields = importFieldTree(List.of(
                new String[]{"Assets", ""},
                new String[]{"Other", "Assets"},
                new String[]{"Other Assets Detail", "Other"},
                new String[]{"Liabilities", ""},
                new String[]{"Other", "Liabilities"},
                new String[]{"Other Liabilities Detail", "Other"}));

        assertEquals(2, topLevelFields.size());
        ReportTemplateFieldDto assetsOther = topLevelFields.get(0).getChildFields().getFirst();
        assertEquals(List.of("Other Assets Detail"), assetsOther.getChildFields().stream().map(ReportTemplateFieldDto::getFieldName).toList());
        ReportTemplateFieldDto liabilitiesOther = topLevelFields.get(1).getChildFields().getFirst();
        assertEquals(List.of("Other Liabilities Detail"), liabilitiesOther.getChildFields().stream().map(ReportTemplateFieldDto::getFieldName).toList());
    }

    /**
     * Imports a single template whose rows are given as {fieldName, parent} pairs and returns the resulting top-level fields.
     */
    private List<ReportTemplateFieldDto> importFieldTree(List<String[]> rows) {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        MultipartFile file = mock(MultipartFile.class);
        ChartOfAccount chartOfAccount = mock(ChartOfAccount.class);

        List<TemplateCsvLine> lines = rows.stream().map(row -> {
            TemplateCsvLine line = mock(TemplateCsvLine.class);
            when(line.getName()).thenReturn("Test Template");
            when(line.getReportType()).thenReturn("Balance sheet");
            when(line.getAccounts()).thenReturn("1234");
            when(line.getFieldName()).thenReturn(row[0]);
            when(line.getParent()).thenReturn(row[1]);
            return line;
        }).toList();
        when(lines.getFirst().getDataMode()).thenReturn("Manual");
        when(lines.getFirst().getActive()).thenReturn("true");
        when(lines.getFirst().getAccountingRegime()).thenReturn("IFRS");

        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any(TemplateCsvLine.class))).thenReturn(errors);
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(new Organisation()));
        when(request.getOrganisationId()).thenReturn("org123");
        when(request.getFile()).thenReturn(file);
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(lines));
        when(chartOfAccountRepository.findById(new ChartOfAccount.Id("org123", "1234"))).thenReturn(Optional.of(chartOfAccount));
        when(chartOfAccount.getId()).thenReturn(new ChartOfAccount.Id("org123", "1234"));
        when(reportTemplateMapper.toEntity(any(ReportTemplateDto.class), any())).thenReturn(mock(ReportTemplateEntity.class));
        when(reportTemplateMapper.toResponseDto(any())).thenReturn(mock(ReportTemplateResponseDto.class));
        when(reportTemplateServiceDependency.validateDataMode(any(ReportTemplateDto.class))).thenReturn(Either.right(null));

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = reportTemplateService.createCsvTemplates(request);

        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
        assertTrue(result.get().getFirst().getError().isEmpty());

        ArgumentCaptor<ReportTemplateDto> dtoCaptor = ArgumentCaptor.forClass(ReportTemplateDto.class);
        verify(reportTemplateMapper).toEntity(dtoCaptor.capture(), any());
        return dtoCaptor.getValue().getFields();
    }

    @Test
    void createCsvTemplates_automaticModeLeafWithoutAccounts_fails() {
        ReportTemplateService realReportTemplateService = new ReportTemplateService(
                reportTemplateRepository,
                reportTemplateMapper,
                chartOfAccountRepository,
                null,
                validator,
                mock(ReportTemplateTypeValidator.class),
                mock(ReportingService.class),
                accountingRegimeResolver);

        CsvReportTemplateService serviceUnderTest = new CsvReportTemplateService(
                organisationPublicApi,
                csvParser,
                reportTemplateRepository,
                null,
                reportTemplateMapper,
                chartOfAccountRepository,
                validator,
                realReportTemplateService);

        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        Organisation organisation = new Organisation();
        MultipartFile file = mock(MultipartFile.class);

        TemplateCsvLine revenueLine = mock(TemplateCsvLine.class);
        TemplateCsvLine assetsLine = mock(TemplateCsvLine.class);

        for (TemplateCsvLine line : List.of(revenueLine, assetsLine)) {
            when(line.getName()).thenReturn("Template CSV 15");
            when(line.getReportType()).thenReturn("Balance sheet");
            when(line.getAccounts()).thenReturn("");
            when(line.getDateRange()).thenReturn("End-of-Period balance");
            when(line.getSign()).thenReturn("Positive");
        }
        when(revenueLine.getDataMode()).thenReturn("Automatic");
        when(revenueLine.getAccountingRegime()).thenReturn("IFRS");
        when(revenueLine.getActive()).thenReturn("true");
        when(revenueLine.getFieldName()).thenReturn("Revenue");
        when(revenueLine.getParent()).thenReturn("");
        when(assetsLine.getFieldName()).thenReturn("Assets");
        when(assetsLine.getParent()).thenReturn("Revenue");

        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any(TemplateCsvLine.class))).thenReturn(errors);

        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getOrganisationId()).thenReturn("org123");
        when(request.getFile()).thenReturn(file);
        when(csvParser.parseCsv(file, TemplateCsvLine.class))
                .thenReturn(Either.right(List.of(revenueLine, assetsLine)));

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = serviceUnderTest.createCsvTemplates(request);

        assertTrue(result.isRight());
        List<ReportTemplateResponseDto> responseDtos = result.get();
        assertEquals(1, responseDtos.size());
        ReportTemplateResponseDto first = responseDtos.getFirst();
        assertTrue(first.getError().isPresent());
        assertEquals("INVALID_FIELD_MAPPINGS", first.getError().get().getTitle());
        assertEquals("Row 1: All fields must have mappings when data mode is Automatic", first.getError().get().getDetail());
        verify(reportTemplateRepository, never()).saveAndFlush(any());
    }

    @Test
    void createCsvTemplates_unknownAccountingRegime_failsAndListsOptions() {
        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = importSingleManualRow("test");

        assertTrue(result.isRight());
        ReportTemplateResponseDto first = result.get().getFirst();
        assertTrue(first.getError().isPresent());
        assertEquals("INVALID_ACCOUNTING_REGIME", first.getError().get().getTitle());
        assertEquals("Row 1: Accounting regime not found: test. Options are: GAAP, IFRS, Not applicable", first.getError().get().getDetail());
        assertEquals(1, first.getError().get().getProperties().get("rowNumber"));
        verify(reportTemplateRepository, never()).saveAndFlush(any());
    }

    @Test
    void createCsvTemplates_inactiveAccountingRegime_fails() {
        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = importSingleManualRow("Old Regime");

        assertTrue(result.isRight());
        ReportTemplateResponseDto first = result.get().getFirst();
        assertTrue(first.getError().isPresent());
        assertEquals("Row 1: Accounting regime not found: Old Regime. Options are: GAAP, IFRS, Not applicable", first.getError().get().getDetail());
        verify(reportTemplateRepository, never()).saveAndFlush(any());
    }

    @Test
    void createCsvTemplates_accountingRegimeMatchesCaseInsensitivelyAndStoresLabel() {
        ArgumentCaptor<ReportTemplateDto> dtoCaptor = ArgumentCaptor.forClass(ReportTemplateDto.class);
        when(reportTemplateMapper.toEntity(dtoCaptor.capture(), isNull())).thenReturn(mock(ReportTemplateEntity.class));
        when(reportTemplateMapper.toResponseDto(any())).thenReturn(ReportTemplateResponseDto.builder().error(Optional.empty()).build());

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = importSingleManualRow(" ifrs ");

        assertTrue(result.isRight());
        assertTrue(result.get().getFirst().getError().isEmpty());
        assertEquals("IFRS", dtoCaptor.getValue().getAccountingRegime());
    }

    @Test
    void createCsvTemplates_notApplicableAccountingRegime_isAccepted() {
        ArgumentCaptor<ReportTemplateDto> dtoCaptor = ArgumentCaptor.forClass(ReportTemplateDto.class);
        when(reportTemplateMapper.toEntity(dtoCaptor.capture(), isNull())).thenReturn(mock(ReportTemplateEntity.class));
        when(reportTemplateMapper.toResponseDto(any())).thenReturn(ReportTemplateResponseDto.builder().error(Optional.empty()).build());

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = importSingleManualRow("Not applicable");

        assertTrue(result.isRight());
        assertTrue(result.get().getFirst().getError().isEmpty());
        assertEquals("Not applicable", dtoCaptor.getValue().getAccountingRegime());
    }

    @Test
    void createCsvTemplates_existingTemplateWithUnchangedDeactivatedRegime_isSaved() {
        ReportTemplateEntity existing = ReportTemplateEntity.builder().id("t1").ver(1L).accountingRegime("Old Regime").build();
        when(reportTemplateRepository.findByOrgnisationIdAndNameAndReportTemplateTypeLatestVersion("org123", "Template CSV", ReportTemplateType.INCOME_STATEMENT))
                .thenReturn(Optional.of(existing));
        when(reportTemplateMapper.toEntity(any(ReportTemplateDto.class), eq(existing))).thenReturn(existing);
        when(reportTemplateMapper.toResponseDto(any())).thenReturn(ReportTemplateResponseDto.builder().error(Optional.empty()).build());

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = importSingleManualRow("Old Regime");

        assertTrue(result.isRight());
        assertTrue(result.get().getFirst().getError().isEmpty());
        verify(reportTemplateRepository).saveAndFlush(existing);
    }

    @Test
    void createCsvTemplates_existingTemplateRegimeChangedToUnknown_fails() {
        ReportTemplateEntity existing = ReportTemplateEntity.builder().id("t1").ver(1L).accountingRegime("IFRS").build();
        when(reportTemplateRepository.findByOrgnisationIdAndNameAndReportTemplateTypeLatestVersion("org123", "Template CSV", ReportTemplateType.INCOME_STATEMENT))
                .thenReturn(Optional.of(existing));

        Either<ProblemDetail, List<ReportTemplateResponseDto>> result = importSingleManualRow("test");

        assertTrue(result.isRight());
        assertEquals("Row 1: Accounting regime not found: test. Options are: GAAP, IFRS, Not applicable",
                result.get().getFirst().getError().get().getDetail());
        verify(reportTemplateRepository, never()).saveAndFlush(any());
    }

    private Either<ProblemDetail, List<ReportTemplateResponseDto>> importSingleManualRow(String accountingRegime) {
        CreateCsvTemplateRequest request = mock(CreateCsvTemplateRequest.class);
        MultipartFile file = mock(MultipartFile.class);
        TemplateCsvLine line = new TemplateCsvLine("Template CSV", "Income statement", "Manual", accountingRegime, "true", "", "Revenue", "", "", "End-of-Period balance", "Positive");
        Errors errors = mock(Errors.class);

        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(line)).thenReturn(errors);
        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(new Organisation()));
        when(request.getOrganisationId()).thenReturn("org123");
        when(request.getFile()).thenReturn(file);
        when(csvParser.parseCsv(file, TemplateCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(reportTemplateServiceDependency.validateDataMode(any(ReportTemplateDto.class))).thenReturn(Either.right(null));

        return reportTemplateService.createCsvTemplates(request);
    }
}
