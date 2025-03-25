package org.cardanofoundation.lob.app.accounting_reporting_core.resource;


import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.ReportViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReportResponseView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReportingParametersView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.ReportService;
import org.cardanofoundation.lob.app.organisation.service.OrganisationCurrencyService;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "lob.accounting_reporting_core.enabled", havingValue = "true", matchIfMissing = true)
public class ReportController {
    private final ReportViewService reportViewService;
    private final OrganisationCurrencyService organisationCurrencyService;
    private final ReportService reportService;

    @Tag(name = "Reporting", description = "Generate Report based on on-chain data")
    @PostMapping(value = "/report-generate", produces = "application/json")
    public ResponseEntity<ReportResponseView> reportGenerate(@Valid @RequestBody ReportGenerateRequest reportGenerateRequest) {
        return reportService.reportGenerate(reportGenerateRequest).fold(
                problem -> {
                    return ResponseEntity.status(problem.getStatus().getStatusCode()).body(ReportResponseView.createFail(problem));
                }, success -> {
                    return ResponseEntity.ok().body(
                            ReportResponseView.createSuccess(List.of(reportViewService.responseView(success)))
                    );
                }
        );
    }

    @Tag(name = "Reporting", description = "Report Parameters")
    @GetMapping(value = "/report-parameters/{orgId}", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
    public ResponseEntity<ReportingParametersView> reportParameters(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {

        HashMap<String, String> currencyOrg = new HashMap<>();
        organisationCurrencyService.findByOrganisationIdAndCode(orgId, "CHF").ifPresent(organisationCurrency ->
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
    @PostMapping(value = "/report-create", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
    public ResponseEntity<ReportResponseView> reportCreate(@Valid @RequestBody ReportRequest reportSaveRequest) {

        return reportViewService.reportCreate(reportSaveRequest)
                .fold(problem -> {
                    return ResponseEntity.status(problem.getStatus().getStatusCode()).body(ReportResponseView.createFail(problem));
                }, success -> {
                    return ResponseEntity.ok().body(
                            ReportResponseView.createSuccess(List.of(reportViewService.responseView(success)))
                    );
                });
    }


    @Tag(name = "Reporting", description = "Create Income Statement")
    @PostMapping(value = "/report-search", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
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
                    ReportResponseView.createSuccess(List.of(reportViewService.responseView(success)))
            );
        });
    }


    @Tag(name = "Reporting", description = "Report list")
    @GetMapping(value = "/report-list/{orgId}", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
    public ResponseEntity<ReportResponseView> reportList(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(ReportResponseView.createSuccess(reportService.findAllByOrgId(
                        orgId
                ).stream().map(reportViewService::responseView).collect(Collectors.toList()))
        );

    }


    @Tag(name = "Reporting", description = "Report publish")
    @PostMapping(value = "/report-publish", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
    public ResponseEntity<ReportResponseView> reportPublish(@Valid @RequestBody ReportPublishRequest reportPublishRequest) {

        return reportViewService.reportPublish(reportPublishRequest).fold(
                problem -> {
                    return ResponseEntity.status(problem.getStatus().getStatusCode()).body(ReportResponseView.createFail(problem));
                }, success -> {
                    return ResponseEntity.ok().body(
                            ReportResponseView.createSuccess(List.of(reportViewService.responseView(success)))
                    );
                }
        );
    }


}
