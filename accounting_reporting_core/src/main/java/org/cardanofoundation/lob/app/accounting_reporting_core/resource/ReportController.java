package org.cardanofoundation.lob.app.accounting_reporting_core.resource;


import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;
import static org.zalando.problem.Status.BAD_REQUEST;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.ReportViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.CreateCsvReportRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportGenerateRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportPublishRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportReprocessRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportSearchRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReportResponseView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReportView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReportingParametersView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.ReportService;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.organisation.service.CurrencyService;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "lob.accounting_reporting_core.enabled", havingValue = "true", matchIfMissing = true)
@Deprecated
public class ReportController {
    private final ReportViewService reportViewService;
    private final CurrencyService currencyService;
    private final ReportService reportService;

    @Tag(name = "Reporting", description = "Generate Report based on on-chain data")
    @PostMapping(value = "/report-generate", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<ReportResponseView> reportGenerate(@Valid @RequestBody ReportGenerateRequest reportGenerateRequest) {
        return reportService.reportGenerate(reportGenerateRequest).fold(
                problem ->
                        ResponseEntity.status(Objects.requireNonNull(problem.getStatus()).getStatusCode()).body(ReportResponseView.createFail(problem)),
                success -> ResponseEntity.ok().body(
                        ReportResponseView.createSuccess(List.of(ReportView.fromEntity(success)))
                )
        );
    }

    @Tag(name = "Reporting", description = "Reprocess Report")
    @PostMapping(value = "/report-reprocess", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<ReportResponseView> reportReprocess(@Valid @RequestBody ReportReprocessRequest reportReprocessRequest) {
        return reportService.reportReprocess(reportReprocessRequest).fold(
                problem -> ResponseEntity.status(Objects.requireNonNull(problem.getStatus()).getStatusCode()).body(ReportResponseView.createFail(problem)),
                success -> ResponseEntity.ok().body(
                        ReportResponseView.createSuccess(List.of(ReportView.fromEntity(success)))
                )
        );
    }

    @Tag(name = "Reporting", description = "Report Parameters")
    @GetMapping(value = "/report-parameters/{orgId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole()) or hasRole(@securityConfig.getAuditorRole())")
    public ResponseEntity<ReportingParametersView> reportParameters(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {

        HashMap<String, String> currencyOrg = new HashMap<>();
        currencyService.findByOrganisationIdAndCode(orgId, "CHF").ifPresent(organisationCurrency ->
                currencyOrg.put(organisationCurrency.getCurrencyId(), organisationCurrency.getId().getCustomerCode()));
        return ResponseEntity.ok().body(
                new ReportingParametersView(
                        Arrays.stream(ReportType.values()).collect(Collectors.toSet()),
                        currencyOrg,
                        "2023"
                )
        );
    }

    @Tag(name = "Reporting", description = "Create Balance Sheet")
    @PostMapping(value = "/report-create", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<ReportResponseView> reportCreate(@Valid @RequestBody ReportRequest reportSaveRequest) {

        return reportViewService.reportCreate(reportSaveRequest)
                .fold(problem -> {
                    return ResponseEntity.status(problem.getStatus().getStatusCode()).body(ReportResponseView.createFail(problem));
                }, success -> {
                    return ResponseEntity.ok().body(
                            ReportResponseView.createSuccess(List.of(ReportView.fromEntity(success)))
                    );
                });
    }

    @Tag(name = "Reporting", description = "Create Balance Sheet from CSV")
    @PostMapping(value = "/report-create", produces = APPLICATION_JSON_VALUE, consumes = MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<List<ReportResponseView>> reportCreateCsv(
                    @Valid @ModelAttribute CreateCsvReportRequest csvReportRequest) {
            return reportViewService.reportCreateCsv(csvReportRequest, true).fold(problem -> {
                    return ResponseEntity.status(BAD_REQUEST.getStatusCode()).body(List.of(ReportResponseView.createFail(problem)));
            }, success -> {
                    return ResponseEntity.ok().body(success.stream().map(t -> t.isRight() ? ReportResponseView.createSuccess(List.of(ReportView.fromEntity(t.get()))) : ReportResponseView.createFail(t.getLeft())).collect(Collectors.toList()));
            });
    }

    @Tag(name = "Reporting", description = "Validate Report CSV")
@PostMapping(value = "/report-validate-csv", produces = APPLICATION_JSON_VALUE, consumes = MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<List<ReportResponseView>> reportValidateCsv(
            @Valid @ModelAttribute CreateCsvReportRequest csvReportRequest) {
        return reportViewService.reportCreateCsv(csvReportRequest, false).fold(problem -> {
                return ResponseEntity.status(BAD_REQUEST.getStatusCode()).body(List.of(ReportResponseView.createFail(problem)));
        }, success -> {
                return ResponseEntity.ok().body(success.stream().map(t -> t.isRight() ? ReportResponseView.createSuccess(List.of(ReportView.fromEntity(t.get()))) : ReportResponseView.createFail(t.getLeft())).collect(Collectors.toList()));
        });
    }



    @Tag(name = "Reporting", description = "Create Income Statement")
    @PostMapping(value = "/report-search", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ReportResponseView> reportSearch(@Valid @RequestBody ReportSearchRequest reportSearchRequest) {

        return reportService.exist(
                reportSearchRequest.getOrganisationId(),
                reportSearchRequest.getReportType(),
                reportSearchRequest.getIntervalType(),
                reportSearchRequest.getYear(),
                reportSearchRequest.getPeriod()
        ).fold(problem -> {
            return ResponseEntity.status(problem.getStatus().getStatusCode()).body(ReportResponseView.createFail(problem));
        }, success -> {
            return ResponseEntity.ok().body(
                    ReportResponseView.createSuccess(List.of(ReportView.fromEntity(success)))
            );
        });
    }


    @Tag(name = "Reporting", description = "Report list")
    @GetMapping(value = "/report-list/{orgId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole()) or hasRole(@securityConfig.getAuditorRole())")
    public ResponseEntity<?> reportList(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                        @RequestParam(value = "reportType", required = false) List<ReportType> reportType,
                                        @RequestParam(value = "currencyCode", required = false) List<String> currencyCode,
                                        @RequestParam(value = "intervalType", required = false) List<IntervalType> intervalType,
                                        @RequestParam(value = "year", required = false) List<Short> year,
                                        @RequestParam(value = "period", required = false) List<Short> period,
                                        @RequestParam(value = "ledgerStatus", required = false) LedgerDispatchStatus status,
                                        @RequestParam(value = "txHash", required = false) String txHash,
                                        @RequestParam(value = "isReadyToPublish", required = false) Boolean readyToPublish,
                                        @RequestParam(value = "ledgerDispatchApproved", required = false) Boolean ledgerDispatchApproved,
                                        @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        return reportService.findAllByOrgId(orgId, reportType, currencyCode, intervalType, year, period, status, txHash, readyToPublish, ledgerDispatchApproved, pageable).fold(
                problem -> ResponseEntity.status(Objects.requireNonNull(problem.getStatus()).getStatusCode()).body(problem),
                ResponseEntity::ok
        );
    }


    @Tag(name = "Reporting", description = "Report publish")
    @PostMapping(value = "/report-publish", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
    public ResponseEntity<ReportResponseView> reportPublish(@Valid @RequestBody ReportPublishRequest reportPublishRequest) {

        return reportViewService.reportPublish(reportPublishRequest).fold(
                problem -> {
                    return ResponseEntity.status(problem.getStatus().getStatusCode()).body(ReportResponseView.createFail(problem));
                }, success -> {
                    return ResponseEntity.ok().body(
                            ReportResponseView.createSuccess(List.of(ReportView.fromEntity(success)))
                    );
                }
        );
    }


}
