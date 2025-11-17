package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.springframework.http.ResponseEntity;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.ReportViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.CreateCsvReportRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportGenerateRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportPublishRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportReprocessRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportSearchRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReportResponseView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReportingParametersView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.ReportService;
import org.cardanofoundation.lob.app.organisation.service.CurrencyService;

@ExtendWith(MockitoExtension.class)
class ReportControllerTest {

        @Mock
        private ReportViewService reportViewService;
        @Mock
        private CurrencyService currencyService;
        @Mock
        private ReportService reportService;

        @InjectMocks
        private ReportController reportController;

        @Test
        void testReportCreate_problem() {
                ReportGenerateRequest reportGenerateRequest =
                                Mockito.mock(ReportGenerateRequest.class);

                Mockito.when(reportService.reportGenerate(reportGenerateRequest))
                                .thenReturn(Either.left(Problem.builder()
                                                .withTitle("REPORT_SETUP_NOT_FOUND")
                                                .withStatus(Status.BAD_REQUEST).build()));

                ResponseEntity<ReportResponseView> reportResponseViewResponseEntity =
                                reportController.reportGenerate(reportGenerateRequest);

                Assertions.assertTrue(reportResponseViewResponseEntity.getStatusCode()
                                .is4xxClientError());
        }

        @Test
        void testReportCreate_success() {
                ReportGenerateRequest reportGenerateRequest =
                                Mockito.mock(ReportGenerateRequest.class);
                ReportEntity reportEntity = Mockito.mock(ReportEntity.class);

                when(reportService.reportGenerate(reportGenerateRequest))
                                .thenReturn(Either.right(reportEntity));
                when(reportEntity.getOrganisation()).thenReturn(Mockito.mock(Organisation.class));
                ResponseEntity<ReportResponseView> reportResponseViewResponseEntity =
                                reportController.reportGenerate(reportGenerateRequest);

                assert reportResponseViewResponseEntity.getStatusCode().is2xxSuccessful();
        }

        @Test
        void reportCsvCreateTest_error() {
                CreateCsvReportRequest request = Mockito.mock(CreateCsvReportRequest.class);
                when(reportViewService.reportCreateCsv(request, true)).thenReturn(
                                Either.left(Problem.builder().withTitle("ORGANISATION_NOT_FOUND")
                                                .withStatus(Status.BAD_REQUEST).build()));
                ResponseEntity<List<ReportResponseView>> response =
                                reportController.reportCreateCsv(request);
                Assertions.assertTrue(response.getStatusCode().is4xxClientError());
        }

        @Test
        void reportCreateTest_successEmpty() {
                CreateCsvReportRequest request = Mockito.mock(CreateCsvReportRequest.class);
                when(reportViewService.reportCreateCsv(request, true))
                                .thenReturn(Either.right(List.of()));
                ResponseEntity<List<ReportResponseView>> response =
                                reportController.reportCreateCsv(request);
                Assertions.assertTrue(response.getStatusCode().is2xxSuccessful());
        }

        @Test
        void reportCsvCreateTest_success() {
                ReportEntity reportEntity = Mockito.mock(ReportEntity.class);
                when(reportEntity.getOrganisation()).thenReturn(Mockito.mock(Organisation.class));
                CreateCsvReportRequest request = Mockito.mock(CreateCsvReportRequest.class);
                when(reportViewService.reportCreateCsv(request, true))
                                .thenReturn(Either.right(List.of(Either.right(reportEntity))));
                ResponseEntity<List<ReportResponseView>> response =
                                reportController.reportCreateCsv(request);
                Assertions.assertTrue(response.getStatusCode().is2xxSuccessful());
        }

        @Test
        void reportCsvCreateTest_successWithFail() {
                CreateCsvReportRequest request = Mockito.mock(CreateCsvReportRequest.class);
                when(reportViewService.reportCreateCsv(request, true))
                                .thenReturn(Either.right(List.of(
                                                Either.left(Problem.builder().build()))));
                ResponseEntity<List<ReportResponseView>> response =
                                reportController.reportCreateCsv(request);
                Assertions.assertTrue(response.getStatusCode().is2xxSuccessful());
        }


        @Test
        void reportCsvValidateTest_error() {
                CreateCsvReportRequest request = Mockito.mock(CreateCsvReportRequest.class);
                when(reportViewService.reportCreateCsv(request, false)).thenReturn(
                                Either.left(Problem.builder().withTitle("ORGANISATION_NOT_FOUND")
                                                .withStatus(Status.BAD_REQUEST).build()));
                ResponseEntity<List<ReportResponseView>> response =
                                reportController.reportValidateCsv(request);
                Assertions.assertTrue(response.getStatusCode().is4xxClientError());
        }

        @Test
        void reportCsvValidateTest_successEmptyList() {
                CreateCsvReportRequest request = Mockito.mock(CreateCsvReportRequest.class);
                when(reportViewService.reportCreateCsv(request, false))
                                .thenReturn(Either.right(List.of()));
                ResponseEntity<List<ReportResponseView>> response =
                                reportController.reportValidateCsv(request);
                Assertions.assertTrue(response.getStatusCode().is2xxSuccessful());
        }

        @Test
        void reportCsvValidateTest_success() {
                ReportEntity reportEntity = Mockito.mock(ReportEntity.class);
                when(reportEntity.getOrganisation()).thenReturn(Mockito.mock(Organisation.class));
                CreateCsvReportRequest request = Mockito.mock(CreateCsvReportRequest.class);
                when(reportViewService.reportCreateCsv(request,
                                false)).thenReturn(Either
                                .right(List.of(Either.right(reportEntity))));
                ResponseEntity<List<ReportResponseView>> response =
                                reportController.reportValidateCsv(request);
                Assertions.assertTrue(response.getStatusCode().is2xxSuccessful());
        }

        @Test
        void reportCsvValidateTest_successWithFail() {
                CreateCsvReportRequest request = Mockito.mock(CreateCsvReportRequest.class);
                when(reportViewService.reportCreateCsv(request, false)).thenReturn(
                                Either.right(List.of(Either.left(Problem.builder().build()))));
                ResponseEntity<List<ReportResponseView>> response =
                                reportController.reportValidateCsv(request);
                Assertions.assertTrue(response.getStatusCode().is2xxSuccessful());
        }

        @Test
        void reportReprocess_problem() {
                ReportReprocessRequest reportReprocessRequest = mock(ReportReprocessRequest.class);
                when(reportService.reportReprocess(reportReprocessRequest))
                                .thenReturn(Either.left(Problem.builder().withStatus(Status.BAD_REQUEST).build()));
                ResponseEntity<ReportResponseView> response =
                                reportController.reportReprocess(reportReprocessRequest);
                assertTrue(response.getStatusCode().is4xxClientError());
        }

        @Test
        void reportReprocess_success() {
                ReportReprocessRequest reportReprocessRequest = mock(ReportReprocessRequest.class);
                ReportEntity reportEntity = mock(ReportEntity.class);
                when(reportService.reportReprocess(reportReprocessRequest))
                                .thenReturn(Either.right(reportEntity));
                when(reportEntity.getOrganisation()).thenReturn(mock(Organisation.class));
                ResponseEntity<ReportResponseView> response =
                                reportController.reportReprocess(reportReprocessRequest);
                assertTrue(response.getStatusCode().is2xxSuccessful());
        }

        @Test
        void reportParameters_success() {
                ResponseEntity<ReportingParametersView> reportParameters = reportController.reportParameters("org123");
                assertTrue(reportParameters.getStatusCode().is2xxSuccessful());
        }

        @Test
        void reportCreate_problem() {
                ReportRequest reportRequest = mock(ReportRequest.class);
                when(reportViewService.reportCreate(reportRequest))
                                .thenReturn(Either.left(Problem.builder().withStatus(Status.BAD_REQUEST).build()));
                ResponseEntity<ReportResponseView> response =
                                reportController.reportCreate(reportRequest);
                assertTrue(response.getStatusCode().is4xxClientError());
        }

        @Test
        void reportCreate_success() {
                ReportRequest reportRequest = mock(ReportRequest.class);
                ReportEntity reportEntity = mock(ReportEntity.class);
                when(reportViewService.reportCreate(reportRequest))
                                .thenReturn(Either.right(reportEntity));
                when(reportEntity.getOrganisation()).thenReturn(mock(Organisation.class));
                ResponseEntity<ReportResponseView> response =
                                reportController.reportCreate(reportRequest);
                assertTrue(response.getStatusCode().is2xxSuccessful());
        }

        @Test
        void reportSearch_problem() {
                ReportSearchRequest reportSearchRequest = mock(ReportSearchRequest.class);
                when(reportSearchRequest.getOrganisationId()).thenReturn("org123");
                when(reportSearchRequest.getReportType()).thenReturn(ReportType.BALANCE_SHEET);
                when(reportSearchRequest.getIntervalType()).thenReturn(IntervalType.MONTH);
                when(reportSearchRequest.getYear()).thenReturn((short) 2023);
                when(reportSearchRequest.getPeriod()).thenReturn((short)1);

                when(reportService.exist(reportSearchRequest.getOrganisationId(), reportSearchRequest.getReportType()
                , reportSearchRequest.getIntervalType(), reportSearchRequest.getYear(), reportSearchRequest.getPeriod()))
                                .thenReturn(Either.left(Problem.builder().withStatus(Status.BAD_REQUEST).build()));
                ResponseEntity<ReportResponseView> response = reportController.reportSearch(reportSearchRequest);
                assertTrue(response.getStatusCode().is4xxClientError());
        }

        @Test
        void reportSearch_success() {
                ReportSearchRequest reportSearchRequest = mock(ReportSearchRequest.class);
                ReportEntity reportEntity = mock(ReportEntity.class);
                when(reportEntity.getOrganisation()).thenReturn(mock(Organisation.class));
                when(reportSearchRequest.getOrganisationId()).thenReturn("org123");
                when(reportSearchRequest.getReportType()).thenReturn(ReportType.BALANCE_SHEET);
                when(reportSearchRequest.getIntervalType()).thenReturn(IntervalType.MONTH);
                when(reportSearchRequest.getYear()).thenReturn((short) 2023);
                when(reportSearchRequest.getPeriod()).thenReturn((short)1);

                when(reportService.exist(reportSearchRequest.getOrganisationId(), reportSearchRequest.getReportType()
                , reportSearchRequest.getIntervalType(), reportSearchRequest.getYear(), reportSearchRequest.getPeriod()))
                                .thenReturn(Either.right(reportEntity));
                ResponseEntity<ReportResponseView> response = reportController.reportSearch(reportSearchRequest);
                assertTrue(response.getStatusCode().is2xxSuccessful());
        }

        @Test
        void reportList_problem() {
                when(reportService.findAllByOrgId("org123", null, null, null, null, null, null, null, null, null, null)).thenReturn(Either.left(Problem.builder().withStatus(Status.BAD_REQUEST).build()));
                ResponseEntity<?> reportList = reportController.reportList("org123", null, null, null, null, null, null, null, null, null, null);
                assertTrue(reportList.getStatusCode().is4xxClientError());
        }

        @Test
        void reportList_success() {
                ReportResponseView reportResponseView = mock(ReportResponseView.class);
                when(reportService.findAllByOrgId("org123", null, null, null, null, null, null, null, null, null, null)).thenReturn(Either.right(reportResponseView));
                ResponseEntity<?> reportList = reportController.reportList("org123", null, null, null, null, null, null, null, null, null, null);
                assertTrue(reportList.getStatusCode().is2xxSuccessful());
        }

        @Test
        void reportPublish_problem() {
                when(reportViewService.reportPublish( Mockito.any())).thenReturn(Either.left(Problem.builder().withStatus(Status.BAD_REQUEST).build()));
                ResponseEntity<ReportResponseView> response = reportController.reportPublish( Mockito.mock(ReportPublishRequest.class));
                assertTrue(response.getStatusCode().is4xxClientError());
        }

        @Test
        void reportPublish_success() {
                ReportEntity reportEntity = mock(ReportEntity.class);
                when(reportEntity.getOrganisation()).thenReturn(mock(Organisation.class));
                when(reportViewService.reportPublish( Mockito.any())).thenReturn(Either.right(reportEntity));
                ResponseEntity<ReportResponseView> response = reportController.reportPublish( Mockito.mock(ReportPublishRequest.class));
                assertTrue(response.getStatusCode().is2xxSuccessful());
        }

}
