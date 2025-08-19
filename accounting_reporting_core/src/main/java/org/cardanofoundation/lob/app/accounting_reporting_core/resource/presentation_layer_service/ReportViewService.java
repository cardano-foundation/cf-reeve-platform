package org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportPublishRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.CreateReportView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.ReportService;

@Service
@org.jmolecules.ddd.annotation.Service
@Slf4j
@RequiredArgsConstructor
@Transactional()
public class ReportViewService {
    private final ReportService reportService;

    @Transactional
    public Either<Problem, ReportEntity> reportPublish(ReportPublishRequest reportPublish) {
        return reportService.approveReportForLedgerDispatch(reportPublish.getReportId());
    }

    @Transactional
    public Either<Problem, ReportEntity> reportCreate(ReportRequest reportSaveRequest) {
        return reportService.storeReport(reportSaveRequest.getReportType(),
                CreateReportView.fromReportRequest(reportSaveRequest),
                reportSaveRequest.getIntervalType(),
                reportSaveRequest.getYear(),
                reportSaveRequest.getPeriod());
    }
}
