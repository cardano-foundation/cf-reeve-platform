package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

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

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.AccountingCorePresentationViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.TransactionsRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.AccountingCoreService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;
import org.cardanofoundation.lob.app.support.security.access.CurrentUserAccess;
import org.cardanofoundation.lob.app.support.security.access.OrganisationAccess;
import org.cardanofoundation.lob.app.support.security.access.PermissionChecker;
import org.cardanofoundation.lob.app.support.security.access.UserAccess;

/**
 * Runs the endpoints' permission expressions for real, for a user who approves and publishes transactions in organisation A and only audits
 * organisation B: the manager rights must not be usable on B, whichever way the organisation arrives.
 */
@SpringJUnitConfig(AccountingCoreOrganisationAccessTest.MethodSecurity.class)
class AccountingCoreOrganisationAccessTest {

    private static final String ORG_A = "org-a";
    private static final String ORG_B = "org-b";

    // Not a @Configuration, so the module's integration tests don't pick it up through their component scan
    @EnableMethodSecurity
    @Import({AccountingCoreResource.class, AccountingCoreResourceReconciliation.class, PermissionChecker.class})
    static class MethodSecurity {
    }

    // What Keycloak gives this user: manager in A, auditor in B
    private static final UserAccess MANAGER_IN_A_AUDITOR_IN_B = new UserAccess("manager-a-auditor-b", Map.of(
            ORG_A, new OrganisationAccess(ORG_A, Set.of("manager"), Set.of(), Set.of("transactions:view", "transactions:export", "transactions:import", "transactions:review_approve", "transactions:publish_approve", "transactions:review_reject", "transactions:batches_view", "transactions:batches_reprocess", "transactions:reconcilate", "transactions:reconcilate_view")),
            ORG_B, new OrganisationAccess(ORG_B, Set.of("auditor"), Set.of(), Set.of("transactions:view", "transactions:batches_view", "transactions:reconcilate_view"))));

    @MockitoBean
    private CurrentUserAccess currentUserAccess;
    @MockitoBean
    private AccountingCorePresentationViewService accountingCorePresentationViewService;
    @MockitoBean
    private AccountingCoreService accountingCoreService;
    @MockitoBean
    private ObjectMapper objectMapper;
    @MockitoBean
    private OrganisationPublicApi organisationPublicApi;
    @MockitoBean
    private KeycloakSecurityHelper keycloakSecurityHelper;
    @MockitoBean
    private JpaSortFieldValidator jpaSortFieldValidator;

    @Autowired
    private AccountingCoreResource accountingCoreResource;
    @Autowired
    private AccountingCoreResourceReconciliation accountingCoreResourceReconciliation;

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
        assertAllowed(() -> accountingCoreResource.downloadTransactionsCsv(ORG_A, null, null, null));
        assertDenied(() -> accountingCoreResource.downloadTransactionsCsv(ORG_B, null, null, null));
    }

    @Test
    void query() {
        assertAllowed(() -> accountingCoreResource.batchReprocess("batch", ORG_A));
        assertDenied(() -> accountingCoreResource.batchReprocess("batch", ORG_B));
        // the auditor role does what it holds in B
        assertAllowed(() -> accountingCoreResource.transactionDetailSpecific("tx", ORG_B));
        assertAllowed(() -> accountingCoreResource.rejectionReasons(ORG_B));
    }

    @Test
    void jsonBody() {
        assertAllowed(() -> accountingCoreResource.approveTransactionsPublish(transactions(ORG_A)));
        assertDenied(() -> accountingCoreResource.approveTransactionsPublish(transactions(ORG_B)));
        assertDenied(() -> accountingCoreResource.approveTransactions(transactions(ORG_B)));
    }

    @Test
    void multipartForm() {
        assertAllowed(() -> accountingCoreResourceReconciliation.reconcileTriggerCsvAction(reconciliation(ORG_A)));
        assertDenied(() -> accountingCoreResourceReconciliation.reconcileTriggerCsvAction(reconciliation(ORG_B)));
    }

    @Test
    void missingOrganisation_isRefused() {
        assertDenied(() -> accountingCoreResource.rejectionReasons(null));
        assertDenied(() -> accountingCoreResource.approveTransactions(transactions(null)));
        assertDenied(() -> accountingCoreResource.approveTransactions(null));
    }

    private static TransactionsRequest transactions(String organisationId) {
        TransactionsRequest request = new TransactionsRequest();
        request.setOrganisationId(organisationId);
        return request;
    }

    private static ReconciliationRequest reconciliation(String organisationId) {
        ReconciliationRequest request = new ReconciliationRequest();
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
