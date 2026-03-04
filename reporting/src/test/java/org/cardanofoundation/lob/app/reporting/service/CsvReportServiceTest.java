package org.cardanofoundation.lob.app.reporting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.reporting.dto.CreateCsvReportRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportCsvLine;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;

@ExtendWith(MockitoExtension.class)
class CsvReportServiceTest {


    @Mock
    private OrganisationPublicApiIF organisationPublicApiIF;
    @Mock
    private CsvParser<ReportCsvLine> csvParser;
    @Mock
    private Validator validator;
    @Mock
    private ReportingService reportingService;
    @Mock
    private ReportTemplateRepository reportTemplateRepository;
    @Mock
    private ReportingRepository reportingRepository;
    @InjectMocks
    private CsvReportService service;


    @Test
    void createCsvReports_orgNotFound() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123")).thenReturn(Optional.empty());

        Either<ProblemDetail, List<ReportResponseDto>> result = service.createCsvReports(request);

        assertTrue(result.isLeft());
        assertEquals("ORGANISATION_NOT_FOUND", result.getLeft().getTitle());
    }

    @Test
    void createCsvReports_csvParserError() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        Organisation organisation = mock(Organisation.class);
        MultipartFile multipartFile = mock(MultipartFile.class);
        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getFile()).thenReturn(multipartFile);
        when(csvParser.parseCsv(multipartFile, ReportCsvLine.class)).thenReturn(Either.left(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "CSV Parsing Error")));

        Either<ProblemDetail, List<ReportResponseDto>> result = service.createCsvReports(request);

        assertTrue(result.isLeft());
        assertEquals("CSV Parsing Error", result.getLeft().getDetail());
    }

    @Test
    void createCsvReports_validateError() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        Organisation organisation = mock(Organisation.class);
        MultipartFile multipartFile = mock(MultipartFile.class);
        ReportCsvLine reportCsvLine = mock(ReportCsvLine.class);
        Errors errors = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);

        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getFile()).thenReturn(multipartFile);
        when(csvParser.parseCsv(multipartFile, ReportCsvLine.class)).thenReturn(Either.right(List.of(reportCsvLine)));
        when(validator.validateObject(reportCsvLine)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(objectError.getDefaultMessage()).thenReturn("CSV Parsing Error");

        Either<ProblemDetail, List<ReportResponseDto>> result = service.createCsvReports(request);

        assertTrue(result.isLeft());
        assertEquals("CSV_PARSING_ERROR", result.getLeft().getTitle());
    }

    @Test
    void createCsvReports_emptyLines() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        Organisation organisation = mock(Organisation.class);
        MultipartFile multipartFile = mock(MultipartFile.class);

        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getFile()).thenReturn(multipartFile);
        when(csvParser.parseCsv(multipartFile, ReportCsvLine.class)).thenReturn(Either.right(List.of()));

        Either<ProblemDetail, List<ReportResponseDto>> result = service.createCsvReports(request);

        assertTrue(result.isLeft());
        assertEquals("CSV_PARSING_ERROR", result.getLeft().getTitle());
        assertEquals("CSV file has no content lines.", result.getLeft().getDetail());
    }

    @Test
    void createCsvReports_templateNotFound() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        Organisation organisation = mock(Organisation.class);
        MultipartFile multipartFile = mock(MultipartFile.class);
        ReportCsvLine reportCsvLine = new ReportCsvLine("Template1", "Report1", "MONTH", (short)2024, (short)1, "SYSTEM", null, null);
        Errors errors = mock(Errors.class);

        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getFile()).thenReturn(multipartFile);
        when(csvParser.parseCsv(multipartFile, ReportCsvLine.class)).thenReturn(Either.right(List.of(reportCsvLine)));
        when(validator.validateObject(reportCsvLine)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());
        Either<ProblemDetail, List<ReportResponseDto>> result = service.createCsvReports(request);

        assertTrue(result.isRight());
        List<ReportResponseDto> reportResponseDtos = result.get();
        assertEquals(1, reportResponseDtos.size());
        assertTrue(reportResponseDtos.getFirst().getError().isPresent());
        assertEquals("REPORT_TEMPLATE_NOT_FOUND", reportResponseDtos.getFirst().getError().get().getTitle());
    }

    @Test
    void createCsvReports_missingFieldName() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        Organisation organisation = mock(Organisation.class);
        MultipartFile multipartFile = mock(MultipartFile.class);
        ReportCsvLine reportCsvLine = new ReportCsvLine("Template1", "Report1", "MONTH", (short)2024, (short)1, "USER", "", "5");
        ReportTemplateEntity reportTemplateEntity = mock(ReportTemplateEntity.class);
        Errors errors = mock(Errors.class);

        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getFile()).thenReturn(multipartFile);
        when(csvParser.parseCsv(multipartFile, ReportCsvLine.class)).thenReturn(Either.right(List.of(reportCsvLine)));
        when(validator.validateObject(reportCsvLine)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(reportTemplateRepository.findLatestByOrganisationIdAndName("org123", reportCsvLine.getTemplateName())).thenReturn(Optional.of(reportTemplateEntity));

        Either<ProblemDetail, List<ReportResponseDto>> result = service.createCsvReports(request);

        assertTrue(result.isRight());
        List<ReportResponseDto> reportResponseDtos = result.get();
        assertEquals(1, reportResponseDtos.size());
        assertTrue(reportResponseDtos.getFirst().getError().isPresent());
        assertEquals("MISSING_FIELD_NAME", reportResponseDtos.getFirst().getError().get().getTitle());
    }

    @Test
    void createCsvReports_invalidAmountFormat() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        Organisation organisation = mock(Organisation.class);
        MultipartFile multipartFile = mock(MultipartFile.class);
        ReportCsvLine reportCsvLine = new ReportCsvLine("Template1", "Report1", "MONTH", (short)2024, (short)1, "USER", "Field1", "5A");
        ReportTemplateEntity reportTemplateEntity = mock(ReportTemplateEntity.class);
        Errors errors = mock(Errors.class);

        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getFile()).thenReturn(multipartFile);
        when(csvParser.parseCsv(multipartFile, ReportCsvLine.class)).thenReturn(Either.right(List.of(reportCsvLine)));
        when(validator.validateObject(reportCsvLine)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(reportTemplateRepository.findLatestByOrganisationIdAndName("org123", reportCsvLine.getTemplateName())).thenReturn(Optional.of(reportTemplateEntity));

        Either<ProblemDetail, List<ReportResponseDto>> result = service.createCsvReports(request);

        assertTrue(result.isRight());
        List<ReportResponseDto> reportResponseDtos = result.get();
        assertEquals(1, reportResponseDtos.size());
        assertTrue(reportResponseDtos.getFirst().getError().isPresent());
        assertEquals("INVALID_AMOUNT_FORMAT", reportResponseDtos.getFirst().getError().get().getTitle());
    }

    @Test
    void createCsvReports_fieldNotFound() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        Organisation organisation = mock(Organisation.class);
        MultipartFile multipartFile = mock(MultipartFile.class);
        ReportCsvLine reportCsvLine = new ReportCsvLine("Template1", "Report1", "MONTH", (short)2024, (short)1, "USER", "Field1", "5");
        ReportTemplateEntity reportTemplateEntity = mock(ReportTemplateEntity.class);

        Errors errors = mock(Errors.class);

        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getFile()).thenReturn(multipartFile);
        when(csvParser.parseCsv(multipartFile, ReportCsvLine.class)).thenReturn(Either.right(List.of(reportCsvLine)));
        when(validator.validateObject(reportCsvLine)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(reportTemplateRepository.findLatestByOrganisationIdAndName("org123", reportCsvLine.getTemplateName())).thenReturn(Optional.of(reportTemplateEntity));
        when(reportTemplateEntity.getFields()).thenReturn(List.of());

        Either<ProblemDetail, List<ReportResponseDto>> result = service.createCsvReports(request);

        assertTrue(result.isRight());
        List<ReportResponseDto> reportResponseDtos = result.get();
        assertEquals(1, reportResponseDtos.size());
        assertTrue(reportResponseDtos.getFirst().getError().isPresent());
        assertEquals("FIELD_NOT_FOUND", reportResponseDtos.getFirst().getError().get().getTitle());
    }

    @Test
    void createCsvReports_success() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        Organisation organisation = mock(Organisation.class);
        MultipartFile multipartFile = mock(MultipartFile.class);
        ReportCsvLine reportCsvLine = new ReportCsvLine("Template1", "Report1", "MONTH", (short)2024, (short)1, "USER", "Field1", "5");
        ReportTemplateEntity reportTemplateEntity = mock(ReportTemplateEntity.class);
        ReportTemplateFieldEntity reportTemplateFieldEntity = mock(ReportTemplateFieldEntity.class);
        ReportResponseDto reportResponseDto = mock(ReportResponseDto.class);
        Errors errors = mock(Errors.class);

        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getFile()).thenReturn(multipartFile);
        when(csvParser.parseCsv(multipartFile, ReportCsvLine.class)).thenReturn(Either.right(List.of(reportCsvLine)));
        when(validator.validateObject(reportCsvLine)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(reportTemplateRepository.findLatestByOrganisationIdAndName("org123", reportCsvLine.getTemplateName())).thenReturn(Optional.of(reportTemplateEntity));
        when(reportTemplateEntity.getFields()).thenReturn(List.of(reportTemplateFieldEntity));
        when(reportTemplateFieldEntity.getName()).thenReturn("Field1");
        when(reportTemplateFieldEntity.getId()).thenReturn(1L);
        when(reportingService.create(any())).thenReturn(reportResponseDto);


        Either<ProblemDetail, List<ReportResponseDto>> result = service.createCsvReports(request);

        assertTrue(result.isRight());
        List<ReportResponseDto> reportResponseDtos = result.get();
        assertEquals(1, reportResponseDtos.size());
        assertEquals(reportResponseDto, reportResponseDtos.getFirst());
    }

    @Test
    void createCsvReports_invalidDataMode() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        Organisation organisation = mock(Organisation.class);
        MultipartFile multipartFile = mock(MultipartFile.class);
        ReportCsvLine reportCsvLine = new ReportCsvLine("Template1", "Report1", "MONTH", (short)2024, (short)1, "USERAA", "Field1", "5");
        ReportTemplateEntity reportTemplateEntity = mock(ReportTemplateEntity.class);
        Errors errors = mock(Errors.class);

        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getFile()).thenReturn(multipartFile);
        when(csvParser.parseCsv(multipartFile, ReportCsvLine.class)).thenReturn(Either.right(List.of(reportCsvLine)));
        when(validator.validateObject(reportCsvLine)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(reportTemplateRepository.findLatestByOrganisationIdAndName("org123", reportCsvLine.getTemplateName())).thenReturn(Optional.of(reportTemplateEntity));

        Either<ProblemDetail, List<ReportResponseDto>> result = service.createCsvReports(request);

        assertTrue(result.isRight());
        List<ReportResponseDto> reportResponseDtos = result.get();
        assertEquals(1, reportResponseDtos.size());
        assertTrue(reportResponseDtos.getFirst().getError().isPresent());
        assertEquals("INVALID_DATA_MODE", reportResponseDtos.getFirst().getError().get().getTitle());
    }

    @Test
    void createCsvReports_multipleLinesWhenSystem() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        Organisation organisation = mock(Organisation.class);
        MultipartFile multipartFile = mock(MultipartFile.class);
        ReportCsvLine reportCsvLine = new ReportCsvLine("Template1", "Report1", "MONTH", (short)2024, (short)1, "SYSTEM", "Field1", "5");
        ReportTemplateEntity reportTemplateEntity = mock(ReportTemplateEntity.class);
        Errors errors = mock(Errors.class);

        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123")).thenReturn(Optional.of(organisation));
        when(request.getFile()).thenReturn(multipartFile);
        when(csvParser.parseCsv(multipartFile, ReportCsvLine.class)).thenReturn(Either.right(List.of(reportCsvLine, reportCsvLine)));
        when(validator.validateObject(reportCsvLine)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(reportTemplateRepository.findLatestByOrganisationIdAndName("org123", reportCsvLine.getTemplateName())).thenReturn(Optional.of(reportTemplateEntity));

        Either<ProblemDetail, List<ReportResponseDto>> result = service.createCsvReports(request);

        assertTrue(result.isRight());
        List<ReportResponseDto> reportResponseDtos = result.get();
        assertEquals(1, reportResponseDtos.size());
        ReportResponseDto first = reportResponseDtos.getFirst();
        assertTrue(first.getError().isPresent());
        assertEquals("MULTIPLE_LINES_FOR_SYSTEM_REPORT", first.getError().get().getTitle());
    }

    @Test
    void downloadReportsAsCsv_emptyList() {
        when(reportingRepository.findAll("org123", null, null, null, null, null, null, null, null, null, null, Pageable.unpaged())).thenReturn(Page.empty());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        service.downloadReportAsCsv("org123", null, null,null, null, null, null, null, null, null, null, outputStream);

        String csv = outputStream.toString(StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");

        assertEquals(1, lines.length); // Only header line should be present
        assertEquals("Template name,Name,Interval type,Period,Year,Data mode,Field name,Amount", lines[0]);
    }

    @Test
    void downloadReportsAsCsv_withData() {
        ReportEntity reportEntity = mock(ReportEntity.class);
        ReportFieldEntity field1 = mock(ReportFieldEntity.class);
        ReportFieldEntity field2 = mock(ReportFieldEntity.class);

        when(field1.getChildFields()).thenReturn(List.of(field2));
        when(field2.getChildFields()).thenReturn(List.of());
        when(reportEntity.getReportTemplate()).thenReturn(mock(ReportTemplateEntity.class));
        when(reportEntity.getReportTemplate().getName()).thenReturn("Template1");
        when(reportEntity.getName()).thenReturn("Report1");
        when(reportEntity.getIntervalType()).thenReturn(IntervalType.MONTH);
        when(reportEntity.getPeriod()).thenReturn((short) 2024);
        when(reportEntity.getYear()).thenReturn((short) 1);
        when(reportEntity.getDataMode()).thenReturn(DataMode.USER);
        when(field1.getFieldTemplate()).thenReturn(mock(ReportTemplateFieldEntity.class));
        when(field1.getFieldTemplate().getName()).thenReturn("Field1");
        when(field2.getFieldTemplate()).thenReturn(mock(ReportTemplateFieldEntity.class));
        when(field2.getFieldTemplate().getName()).thenReturn("Field2");
        when(field2.getValue()).thenReturn(BigDecimal.valueOf(5));


        when(reportEntity.getFields()).thenReturn(List.of(field1));
        when(reportingRepository.findAll("org123", null, null, null, null, null, null, null, null, null, null, Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(reportEntity), PageRequest.of(0,1), 1));

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        service.downloadReportAsCsv("org123", null, null, null, null, null, null, null, null, null, null, outputStream);

        String csv = outputStream.toString(StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");

        assertEquals(2, lines.length); // Header + 1 data line
        assertEquals("Template name,Name,Interval type,Period,Year,Data mode,Field name,Amount", lines[0]);
        assertEquals("Template1,Report1,MONTH,2024,1,USER,Field1.Field2,5", lines[1]);
    }
}
