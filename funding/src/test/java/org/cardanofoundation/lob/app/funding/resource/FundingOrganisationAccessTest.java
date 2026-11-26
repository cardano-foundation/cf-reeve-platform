package org.cardanofoundation.lob.app.funding.resource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.funding.domain.csv.FundingCsvFileType;
import org.cardanofoundation.lob.app.funding.domain.request.BulkImportRequest;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingEventBulkPublishRequest;
import org.cardanofoundation.lob.app.funding.service.FundingBulkImportService;
import org.cardanofoundation.lob.app.funding.service.FundingCsvExportService;
import org.cardanofoundation.lob.app.funding.service.FundingCsvTemplateService;
import org.cardanofoundation.lob.app.funding.service.SpendingEventService;
import org.cardanofoundation.lob.app.support.security.access.CurrentUserAccess;
import org.cardanofoundation.lob.app.support.security.access.OrganisationAccess;
import org.cardanofoundation.lob.app.support.security.access.PermissionChecker;
import org.cardanofoundation.lob.app.support.security.access.UserAccess;

/**
 * Runs the endpoints' permission expressions for real, for a user who manages projects and events in organisation A and only audits
 * organisation B: the manager rights must not be usable on B, whichever way the organisation arrives.
 */
@SpringJUnitConfig(FundingOrganisationAccessTest.MethodSecurity.class)
class FundingOrganisationAccessTest {

    private static final String ORG_A = "org-a";
    private static final String ORG_B = "org-b";

    // Not a @Configuration, so the module's integration tests don't pick it up through their component scan
    @EnableMethodSecurity
    @Import({SpendingEventController.class, FundingBulkImportController.class, PermissionChecker.class})
    static class MethodSecurity {
    }

    // What Keycloak gives this user: manager in A, auditor in B
    private static final UserAccess MANAGER_IN_A_AUDITOR_IN_B = new UserAccess("manager-a-auditor-b", Map.of(
            ORG_A, new OrganisationAccess(ORG_A, Set.of("manager"), Set.of(), Set.of("project_management:view", "project_management:create", "project_management:edit", "project_management:delete", "project_management:publish", "project_management:create_project")),
            ORG_B, new OrganisationAccess(ORG_B, Set.of("auditor"), Set.of(), Set.of("project_management:view"))));

    @MockitoBean
    private CurrentUserAccess currentUserAccess;
    @MockitoBean
    private SpendingEventService spendingEventService;
    @MockitoBean
    private FundingBulkImportService fundingBulkImportService;
    @MockitoBean
    private FundingCsvTemplateService fundingCsvTemplateService;
    @MockitoBean
    private FundingCsvExportService fundingCsvExportService;

    @Autowired
    private SpendingEventController spendingEventController;
    @Autowired
    private FundingBulkImportController fundingBulkImportController;

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
        assertAllowed(() -> spendingEventController.deleteOrphanedErrorEvents(ORG_A));
        assertDenied(() -> spendingEventController.deleteOrphanedErrorEvents(ORG_B));
        assertAllowed(() -> fundingBulkImportController.downloadTemplate(FundingCsvFileType.EVENTS, ORG_A));
        assertDenied(() -> fundingBulkImportController.downloadTemplate(FundingCsvFileType.EVENTS, ORG_B));
        // the auditor role does what it holds in B
        assertAllowed(() -> spendingEventController.eventTypes(ORG_B));
    }

    @Test
    void idOnly() {
        assertAllowed(() -> spendingEventController.deleteEvent("event", ORG_A));
        assertDenied(() -> spendingEventController.deleteEvent("event", ORG_B));
        assertAllowed(() -> spendingEventController.getEvent("event", ORG_B));
    }

    @Test
    void jsonBody() {
        assertAllowed(() -> spendingEventController.publishEvents(publish(ORG_A)));
        assertDenied(() -> spendingEventController.publishEvents(publish(ORG_B)));
    }

    @Test
    void multipartForm() {
        assertAllowed(() -> fundingBulkImportController.bulkImport(bulkImport(ORG_A)));
        assertDenied(() -> fundingBulkImportController.bulkImport(bulkImport(ORG_B)));
    }

    @Test
    void missingOrganisation_isRefused() {
        assertDenied(() -> spendingEventController.eventStatuses(null));
        assertDenied(() -> spendingEventController.publishEvents(publish(null)));
    }

    private static SpendingEventBulkPublishRequest publish(String organisationId) {
        SpendingEventBulkPublishRequest request = new SpendingEventBulkPublishRequest();
        request.setOrganisationId(organisationId);
        return request;
    }

    private static BulkImportRequest bulkImport(String organisationId) {
        BulkImportRequest request = new BulkImportRequest();
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
