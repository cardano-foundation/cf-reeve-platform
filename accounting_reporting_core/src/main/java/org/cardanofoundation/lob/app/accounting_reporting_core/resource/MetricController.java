package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.metric.GetMetricDataRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.metric.SaveDashboardRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.metric.UpdateDashboardRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.response.metric.MetricDataResponse;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.metric.DashboardView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.metric.MetricView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.metrics.MetricService;

@RestController
@RequestMapping("/api/v1/metrics")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "lob.accounting_reporting_core.enabled", havingValue = "true", matchIfMissing = true)
public class MetricController {

    private final MetricService metricService;

    @Tag(name = "Metrics", description = "Available Metrics")
    @GetMapping(value = "/availableMetrics", produces = "application/json")
    public ResponseEntity<MetricView> availableDashboards() {
        return ResponseEntity.ok(new MetricView(metricService.getAvailableMetrics()));
    }

    @Tag(name = "Metrics", description = "Get Data from Metrics")
    @PostMapping(value = "/data", produces = "application/json")
    public ResponseEntity<MetricDataResponse> getDashboardData(@RequestBody GetMetricDataRequest getMetricDataRequest) {
        return ResponseEntity.ok(new MetricDataResponse(metricService.getData(
                getMetricDataRequest.getMetricView().getMetrics(),
                getMetricDataRequest.getOrganisationId(),
                Optional.ofNullable(getMetricDataRequest.getStartDate()),
                Optional.ofNullable(getMetricDataRequest.getEndDate()))));
    }

    @Tag(name = "Dashboards", description = "Save Dashboards")
    @PostMapping(value = "/saveDashboard", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
    public ResponseEntity<Boolean> saveDashboard(@RequestBody SaveDashboardRequest saveDashboardRequest) {
        boolean success = metricService.saveDashboard(saveDashboardRequest.getDashboards(), saveDashboardRequest.getOrganisationId());
        if (success) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

    @Tag(name = "Dashboards", description = "Get Dashboards")
    @GetMapping(value = "/dashboards/{organisationID}", produces = "application/json")
    public ResponseEntity<List<DashboardView>> getDashboards(@PathVariable("organisationID") String organisationID) {
        return ResponseEntity.ok(metricService.getAllDashboards(organisationID));
    }

    @Tag(name = "Dashboards", description = "Delete Dashboards")
    @DeleteMapping(value = "/deleteDashboard/{organisationID}/{dashboardID}", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
    public ResponseEntity<Void> deleteDashboard(@PathVariable("organisationID") String organisationID, @PathVariable("dashboardID") Long dashboardID) {
        metricService.deleteDashboard(organisationID, dashboardID);
        return ResponseEntity.ok().build();
    }

    @Tag(name = "Dashboards", description = "Update Dashboards")
    @PostMapping(value = "/updateDashboard", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
    public ResponseEntity<Boolean> updateDashboard(@RequestBody UpdateDashboardRequest updateDashboardRequest) {
        metricService.updateDashboard(updateDashboardRequest.getDashboard(), updateDashboardRequest.getOrganisationId());
        return ResponseEntity.ok().build();
    }
}
