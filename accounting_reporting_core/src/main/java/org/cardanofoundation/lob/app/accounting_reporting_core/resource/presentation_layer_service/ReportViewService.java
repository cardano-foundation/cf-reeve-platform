package org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service;


import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportCsvLine;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.CreateCsvReportRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportPublishRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.CreateReportView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.ReportService;
import org.cardanofoundation.lob.app.accounting_reporting_core.utils.Constants;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.support.security.AntiVirusScanner;

@Service
@org.jmolecules.ddd.annotation.Service
@Slf4j
@RequiredArgsConstructor
@Transactional()
public class ReportViewService {
    private final ReportService reportService;
    private final AntiVirusScanner antiVirusScanner;
    private final CsvParser<ReportCsvLine> csvParser;
    private final Validator validator;
    private final OrganisationPublicApiIF organisationPublicApiIF;

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

    /**
     * Creates reports from a CSV file upload.
     * The store flag is to control wether the reports will be stored right away or not.
     * If false can be used as a validation.
     */
    public Either<Problem, List<Either<Problem, ReportEntity>>> reportCreateCsv(
            CreateCsvReportRequest csvReportRequest, boolean store) {
        Optional<Organisation> organisationO = organisationPublicApiIF.findByOrganisationId(csvReportRequest.getOrganisationId());
        if(organisationO.isEmpty()) {
            Problem problem = Problem.builder()
                    .withTitle(Constants.ORGANISATION_NOT_FOUND)
                    .withDetail("Organisation with id " + csvReportRequest.getOrganisationId() + " not found.")
                    .withStatus(Status.BAD_REQUEST)
                    .build();
            return Either.left(problem);
        }
        Either<Problem, byte[]> fileBytes = antiVirusScanner.readFileBytes(Optional.of(csvReportRequest.getFile()));
        if (fileBytes.isLeft()) {
            return Either.left(fileBytes.getLeft());
        }
        Either<Problem, List<ReportCsvLine>> parsedLines = csvParser.parseCsv(
                fileBytes.get(), ReportCsvLine.class);
        if(parsedLines.isLeft()) {
            return Either.left(parsedLines.getLeft());
        }
        // Create a mutable copy of the parsed lines
        List<ReportCsvLine> reportCsvLines = new ArrayList<>(parsedLines.get());
        Either<List<Problem>, Void> validationResult = validateReportCsvLine(reportCsvLines);
        if (validationResult.isLeft()) {
            return Either.left(validationResult.getLeft().get(0));
        }
        List<Either<Problem, ReportEntity>> storeCsvReports = reportService.storeCsvReports(reportCsvLines, csvReportRequest.getOrganisationId(), store);
        return Either.right(storeCsvReports);
    }

    private Either<List<Problem>, Void> validateReportCsvLine(List<ReportCsvLine> reportCsvLines) {
        List<Problem> problems = new ArrayList<>();
        for(ReportCsvLine reportCsvLine : reportCsvLines) {
            Errors validateObject = validator.validateObject(reportCsvLine);
            List<ObjectError> allErrors = validateObject.getAllErrors();
            if (!allErrors.isEmpty()) {
                Problem error = Problem.builder()
                        .withTitle(Constants.CSV_PARSING_ERROR)
                        .withDetail(allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")))
                        .withStatus(Status.BAD_REQUEST)
                        .build();
                problems.add(error);
            }
        }
        if(problems.size() > 0) {
            return Either.left(problems);
        }
        return Either.right(null);
    }
}
