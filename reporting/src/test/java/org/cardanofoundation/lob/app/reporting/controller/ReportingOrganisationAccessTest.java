package org.cardanofoundation.lob.app.reporting.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.reporting.dto.CreateCsvTemplateRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportIdRequest;
import org.cardanofoundation.lob.app.reporting.service.CsvReportService;
import org.cardanofoundation.lob.app.reporting.service.CsvReportTemplateService;
import org.cardanofoundation.lob.app.reporting.service.ReportTemplateService;
import org.cardanofoundation.lob.app.reporting.service.ReportingService;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;
import org.cardanofoundation.lob.app.support.security.access.CurrentUserAccess;
import org.cardanofoundation.lob.app.support.security.access.OrganisationAccess;
import org.cardanofoundation.lob.app.support.security.access.PermissionChecker;
import org.cardanofoundation.lob.app.support.security.access.UserAccess;

/**
 * Runs the endpoints' permission expressions for real, for a user who manages reports in organisation A and only
 * audits organisation B: the manager rights must not be usable on B, whichever way the organisation arrives.
 */
@SpringJUnitConfig(ReportingOrganisationAccessTest.MethodSecurity.class)
class ReportingOrganisationAccessTest {

    private static final String ORG_A = "org-a";
    private static final String ORG_B = "org-b";

    // Not a @Configuration, so the module's integration tests don't pick it up through their component scan
    @EnableMethodSecurity
    @Import({ReportingController.class, ReportTemplateController.class, PermissionChecker.class})
    static class MethodSecurity {
    }

    // What Keycloak gives this user: reports manager in A, auditor in B
    private static final UserAccess MANAGER_IN_A_AUDITOR_IN_B = new UserAccess("manager-a-auditor-b", Map.of(
            ORG_A, new OrganisationAccess(ORG_A, Set.of("reports_manager"), Set.of(), Set.of(
                    "reports:view", "reports:create", "reports:edit", "reports:delete", "reports:publish",
                    "reports:reject", "report_templates:view", "report_templates:create",
                    "report_templates:edit", "report_templates:delete")),
            ORG_B, new OrganisationAccess(ORG_B, Set.of("reports_viewer"), Set.of(), Set.of(
                    "reports:view", "report_templates:view"))));

    @MockitoBean
    private CurrentUserAccess currentUserAccess;
    @MockitoBean
    private ReportingService reportingService;
    @MockitoBean
    private CsvReportService csvReportService;
    @MockitoBean
    private ReportTemplateService reportTemplateService;
    @MockitoBean
    private CsvReportTemplateService csvReportTemplateService;
    @MockitoBean
    private KeycloakSecurityHelper keycloakSecurityHelper;
    @MockitoBean
    private JpaSortFieldValidator jpaSortFieldValidator;

    @Autowired
    private ReportingController reports;
    @Autowired
    private ReportTemplateController templates;

    @BeforeEach
    void signIn() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("manager-a-auditor-b", null, "user"));
        when(currentUserAccess.get()).thenReturn(MANAGER_IN_A_AUDITOR_IN_B);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void query() {
        assertAllowed(() -> reports.reprocess("report", ORG_A));
        assertDenied(() -> reports.reprocess("report", ORG_B));
        // the auditor role does what it holds in B
        assertAllowed(() -> reports.findAll(ORG_B, null, null, null, null, null, null, null, null, null, Pageable.unpaged()));
    }

    @Test
    void idOnly() {
        assertAllowed(() -> reports.delete("report", ORG_A));
        assertDenied(() -> reports.delete("report", ORG_B));
        assertAllowed(() -> templates.delete("template", ORG_A));
        assertDenied(() -> templates.delete("template", ORG_B));
    }

    @Test
    void jsonBody() {
        assertAllowed(() -> reports.publish(reportIdRequest(ORG_A)));
        assertDenied(() -> reports.publish(reportIdRequest(ORG_B)));
        assertDenied(() -> reports.reject(reportIdRequest(ORG_B)));
    }

    @Test
    void multipartForm() {
        assertAllowed(() -> templates.templateCreateCsv(csvTemplateRequest(ORG_A)));
        assertDenied(() -> templates.templateCreateCsv(csvTemplateRequest(ORG_B)));
    }

    @Test
    void missingOrganisation_isRefused() {
        assertDenied(() -> reports.findById("report", null));
        assertDenied(() -> templates.getReportTypes(""));
        assertDenied(() -> reports.publish(reportIdRequest(null)));
        assertDenied(() -> reports.publish(null));
    }

    @Test
    void organisationTheUserIsNotIn_isRefused() {
        assertDenied(() -> templates.findById("template", "org-c"));
    }

    private static ReportIdRequest reportIdRequest(String organisationId) {
        ReportIdRequest request = new ReportIdRequest();
        request.setOrganisationId(organisationId);
        request.setReportId("report");
        return request;
    }

    private static CreateCsvTemplateRequest csvTemplateRequest(String organisationId) {
        CreateCsvTemplateRequest request = new CreateCsvTemplateRequest();
        request.setOrganisationId(organisationId);
        return request;
    }

    private static void assertDenied(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(AccessDeniedException.class);
    }

    // Past the permission check; what the mocked services return afterwards doesn't matter here
    private static void assertAllowed(Runnable call) {
        try {
            call.run();
        } catch (AccessDeniedException e) {
            fail("expected the permission check to pass", e);
        } catch (RuntimeException ignored) {
            // the endpoint ran
        }
    }

}
