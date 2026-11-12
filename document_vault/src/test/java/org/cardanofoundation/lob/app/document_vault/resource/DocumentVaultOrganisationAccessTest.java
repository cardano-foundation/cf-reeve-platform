package org.cardanofoundation.lob.app.document_vault.resource;

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

import org.cardanofoundation.lob.app.document_vault.domain.request.RegisterKeyRequest;
import org.cardanofoundation.lob.app.document_vault.domain.request.UploadDocumentRequest;
import org.cardanofoundation.lob.app.document_vault.service.RecipientResolutionService;
import org.cardanofoundation.lob.app.document_vault.service.VaultDocumentService;
import org.cardanofoundation.lob.app.document_vault.service.VaultKeyService;
import org.cardanofoundation.lob.app.support.security.access.CurrentUserAccess;
import org.cardanofoundation.lob.app.support.security.access.OrganisationAccess;
import org.cardanofoundation.lob.app.support.security.access.PermissionChecker;
import org.cardanofoundation.lob.app.support.security.access.UserAccess;

/**
 * Runs the endpoints' permission expressions for real, for a user who manages the document vault in organisation A and only audits
 * organisation B: the manager rights must not be usable on B, whichever way the organisation arrives.
 */
@SpringJUnitConfig(DocumentVaultOrganisationAccessTest.MethodSecurity.class)
class DocumentVaultOrganisationAccessTest {

    private static final String ORG_A = "org-a";
    private static final String ORG_B = "org-b";

    // Not a @Configuration, so the module's integration tests don't pick it up through their component scan
    @EnableMethodSecurity
    @Import({VaultDocumentController.class, VaultKeyController.class, PermissionChecker.class})
    static class MethodSecurity {
    }

    // What Keycloak gives this user: manager in A, auditor in B
    private static final UserAccess MANAGER_IN_A_AUDITOR_IN_B = new UserAccess("manager-a-auditor-b", Map.of(
            ORG_A, new OrganisationAccess(ORG_A, Set.of("manager"), Set.of(), Set.of("document_vault:view", "document_vault:create", "document_vault:edit", "document_vault:delete", "document_vault:publish")),
            ORG_B, new OrganisationAccess(ORG_B, Set.of("auditor"), Set.of(), Set.of("document_vault:view"))));

    @MockitoBean
    private CurrentUserAccess currentUserAccess;
    @MockitoBean
    private VaultDocumentService vaultDocumentService;
    @MockitoBean
    private VaultKeyService vaultKeyService;
    @MockitoBean
    private RecipientResolutionService recipientResolutionService;

    @Autowired
    private VaultDocumentController vaultDocumentController;
    @Autowired
    private VaultKeyController vaultKeyController;

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
        assertAllowed(() -> vaultKeyController.listOrganisationKeys(ORG_B, Pageable.unpaged()));
        assertDenied(() -> vaultKeyController.listOrganisationKeys("org-c", Pageable.unpaged()));
    }

    @Test
    void idOnly() {
        assertAllowed(() -> vaultDocumentController.publish("document", ORG_A, null));
        assertDenied(() -> vaultDocumentController.publish("document", ORG_B, null));
        assertAllowed(() -> vaultDocumentController.delete("document", ORG_A));
        assertDenied(() -> vaultDocumentController.delete("document", ORG_B));
        assertDenied(() -> vaultKeyController.deleteKey("key", ORG_B));
        // reading is what the auditor holds in B
        assertAllowed(() -> vaultDocumentController.fetch("document", ORG_B));
    }

    @Test
    void jsonBody() {
        assertAllowed(() -> vaultDocumentController.upload(upload(ORG_A)));
        assertDenied(() -> vaultDocumentController.upload(upload(ORG_B)));
        assertDenied(() -> vaultKeyController.registerKey(registerKey(ORG_B)));
    }

    @Test
    void missingOrganisation_isRefused() {
        assertDenied(() -> vaultKeyController.listMyKeys(null, Pageable.unpaged()));
        assertDenied(() -> vaultDocumentController.upload(upload(" ")));
        assertDenied(() -> vaultDocumentController.upload(null));
    }

    private static UploadDocumentRequest upload(String organisationId) {
        UploadDocumentRequest request = new UploadDocumentRequest();
        request.setOrganisationId(organisationId);
        return request;
    }

    private static RegisterKeyRequest registerKey(String organisationId) {
        RegisterKeyRequest request = new RegisterKeyRequest();
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
