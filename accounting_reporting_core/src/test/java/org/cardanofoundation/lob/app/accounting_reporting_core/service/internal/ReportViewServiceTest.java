package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

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

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportCsvLine;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.ReportViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.CreateCsvReportRequest;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.support.security.AntiVirusScanner;

@ExtendWith(MockitoExtension.class)
class ReportViewServiceTest {

    @Mock
    private ReportService reportService;
    @Mock
    private AntiVirusScanner antiVirusScanner;
    @Mock
    private CsvParser<ReportCsvLine> csvParser;
    @Mock
    private Validator validator;
    @Mock
    private OrganisationPublicApiIF organisationPublicApiIF;
    @InjectMocks
    private ReportViewService reportViewService;



    @Test
    void reportCreateCsv_orgNotFound() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);

        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123")).thenReturn(Optional.empty());


        Either<Problem, List<Either<Problem, ReportEntity>>> response = reportViewService.reportCreateCsv(request, false);
        assertTrue(response.isLeft());

    }

    @Test
    void reportCreateCsv_antivirus() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        MultipartFile file = mock(MultipartFile.class);
        when(request.getFile()).thenReturn(file);
        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123"))
                .thenReturn(Optional.of(mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class)));
        when(antiVirusScanner.readFileBytes(Optional.of(
                file))).thenReturn(Either.left(Problem.builder().build()));
        Either<Problem, List<Either<Problem, ReportEntity>>> response = reportViewService.reportCreateCsv(request, false);
        assertTrue(response.isLeft());

    }

    @Test
    void reportCreateCsv_csvParse() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        MultipartFile file = mock(MultipartFile.class);
        when(request.getFile()).thenReturn(file);
        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123")).thenReturn(Optional.of(
                mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class)));
        when(antiVirusScanner.readFileBytes(Optional.of(file)))
                .thenReturn(Either.right(new byte[]{}));
        when(csvParser.parseCsv(new byte[]{}, ReportCsvLine.class))
                .thenReturn(Either.left(Problem.builder().build()));
        Either<Problem, List<Either<Problem, ReportEntity>>> response =
                reportViewService.reportCreateCsv(request, false);
        assertTrue(response.isLeft());
    }

    @Test
    void reportCreateCsv_validateError() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        MultipartFile file = mock(MultipartFile.class);
        ReportCsvLine csvLine = mock(ReportCsvLine.class);
        Errors errors = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(request.getFile()).thenReturn(file);
        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123")).thenReturn(Optional.of(
                mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class)));
        when(antiVirusScanner.readFileBytes(Optional.of(file)))
                .thenReturn(Either.right(new byte[] {}));
        when(csvParser.parseCsv(new byte[] {}, ReportCsvLine.class))
                .thenReturn(Either.right(List.of(csvLine)));
        when(validator.validateObject(csvLine)).thenReturn(errors);
        Either<Problem, List<Either<Problem, ReportEntity>>> response =
                reportViewService.reportCreateCsv(request, false);
        assertTrue(response.isLeft());

    }

    @Test
    void reportCreateCsv_success() {
        CreateCsvReportRequest request = mock(CreateCsvReportRequest.class);
        MultipartFile file = mock(MultipartFile.class);
        when(request.getFile()).thenReturn(file);
        when(request.getOrganisationId()).thenReturn("org123");
        when(organisationPublicApiIF.findByOrganisationId("org123")).thenReturn(Optional.of(
                mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class)));
        when(antiVirusScanner.readFileBytes(Optional.of(file)))
                .thenReturn(Either.right(new byte[] {}));
        when(csvParser.parseCsv(new byte[] {}, ReportCsvLine.class))
                .thenReturn(Either.right(List.of()));
        Either<Problem, List<Either<Problem, ReportEntity>>> response =
                reportViewService.reportCreateCsv(request, false);
        assertTrue(response.isRight());
    }
}
