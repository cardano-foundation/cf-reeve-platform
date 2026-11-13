package org.cardanofoundation.lob.app.organisation.resource;

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
import org.springframework.web.multipart.MultipartFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.organisation.service.CostCenterService;
import org.cardanofoundation.lob.app.organisation.service.ReportTypeService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;
import org.cardanofoundation.lob.app.support.security.access.CurrentUserAccess;
import org.cardanofoundation.lob.app.support.security.access.OrganisationAccess;
import org.cardanofoundation.lob.app.support.security.access.PermissionChecker;
import org.cardanofoundation.lob.app.support.security.access.UserAccess;

/**
 * Runs the endpoints' permission expressions for real, for a user who manages the organisation setup in organisation A and only audits
 * organisation B: the manager rights must not be usable on B, whichever way the organisation arrives.
 */
@SpringJUnitConfig(OrganisationSetupOrganisationAccessTest.MethodSecurity.class)
class OrganisationSetupOrganisationAccessTest {

    private static final String ORG_A = "org-a";
    private static final String ORG_B = "org-b";

    // Not a @Configuration, so the module's integration tests don't pick it up through their component scan
    @EnableMethodSecurity
    @Import({CostCenterController.class, ReportTypeController.class, PermissionChecker.class})
    static class MethodSecurity {
    }

    // What Keycloak gives this user: manager in A, auditor in B
    private static final UserAccess MANAGER_IN_A_AUDITOR_IN_B = new UserAccess("manager-a-auditor-b", Map.of(
            ORG_A, new OrganisationAccess(ORG_A, Set.of("manager"), Set.of(), Set.of("cost_centers:view", "cost_centers:create", "cost_centers:edit", "report_types:view", "report_types:edit", "export_setup_data:download")),
            ORG_B, new OrganisationAccess(ORG_B, Set.of("auditor"), Set.of(), Set.of("cost_centers:view", "report_types:view"))));

    @MockitoBean
    private CurrentUserAccess currentUserAccess;
    @MockitoBean
    private CostCenterService costCenterService;
    @MockitoBean
    private ReportTypeService reportTypeService;
    @MockitoBean
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @Autowired
    private CostCenterController costCenterController;
    @Autowired
    private ReportTypeController reportTypeController;

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
    void path() {
        assertAllowed(() -> costCenterController.insertCostCenters(ORG_A, null));
        assertDenied(() -> costCenterController.insertCostCenters(ORG_B, null));
        // the auditor role does what it holds in B
        assertAllowed(() -> costCenterController.getAllCostCenters(ORG_B, null, null, null, null, Pageable.unpaged()));
        assertDenied(() -> costCenterController.downloadCostCentersCsv(ORG_B, null, null, null, null));
    }

    @Test
    void multipartForm() {
        assertAllowed(() -> reportTypeController.addMappingToReportTypeField(ORG_A, (MultipartFile) null));
        assertDenied(() -> reportTypeController.addMappingToReportTypeField(ORG_B, (MultipartFile) null));
    }

    @Test
    void missingOrganisation_isRefused() {
        assertDenied(() -> reportTypeController.getReferenceCodes(null));
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
