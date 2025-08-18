package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

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

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.ReportViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportGenerateRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReportResponseView;
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
        ReportGenerateRequest reportGenerateRequest = Mockito.mock(ReportGenerateRequest.class);

        Mockito.when(reportService.reportGenerate(reportGenerateRequest)).thenReturn(Either.left(Problem.builder()
                .withTitle("REPORT_SETUP_NOT_FOUND")
                .withStatus(Status.BAD_REQUEST)
                .build()));

        ResponseEntity<ReportResponseView> reportResponseViewResponseEntity = reportController.reportGenerate(reportGenerateRequest);

        Assertions.assertTrue(reportResponseViewResponseEntity.getStatusCode().is4xxClientError());
    }

    @Test
    void testReportCreate_success() {
        ReportGenerateRequest reportGenerateRequest = Mockito.mock(ReportGenerateRequest.class);
        ReportEntity reportEntity = Mockito.mock(ReportEntity.class);

        Mockito.when(reportService.reportGenerate(reportGenerateRequest)).thenReturn(Either.right(reportEntity));
        ResponseEntity<ReportResponseView> reportResponseViewResponseEntity = reportController.reportGenerate(reportGenerateRequest);

        assert reportResponseViewResponseEntity.getStatusCode().is2xxSuccessful();
    }

}
