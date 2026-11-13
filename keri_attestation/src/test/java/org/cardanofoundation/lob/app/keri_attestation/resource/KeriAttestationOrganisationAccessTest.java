package org.cardanofoundation.lob.app.keri_attestation.resource;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.CeremonyService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriAgentService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriAttestService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriAuthBeginService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriCredentialService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriOobiService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;
import org.cardanofoundation.lob.app.support.security.access.CurrentUserAccess;
import org.cardanofoundation.lob.app.support.security.access.OrganisationAccess;
import org.cardanofoundation.lob.app.support.security.access.PermissionChecker;
import org.cardanofoundation.lob.app.support.security.access.UserAccess;

/**
 * Runs the endpoints' permission expressions for real, for a user who can publish documents in organisation A and only audits
 * organisation B: the manager rights must not be usable on B, whichever way the organisation arrives.
 */
@SpringJUnitConfig(KeriAttestationOrganisationAccessTest.MethodSecurity.class)
// The controller only exists with a KERIA agent configured
@TestPropertySource(properties = "lob.keri-attestation.keria.url=http://localhost:3901")
class KeriAttestationOrganisationAccessTest {

    private static final String ORG_A = "org-a";
    private static final String ORG_B = "org-b";

    // Not a @Configuration, so the module's integration tests don't pick it up through their component scan
    @EnableMethodSecurity
    @Import({KeriAttestationController.class, PermissionChecker.class})
    static class MethodSecurity {
    }

    // What Keycloak gives this user: manager in A, auditor in B
    private static final UserAccess MANAGER_IN_A_AUDITOR_IN_B = new UserAccess("manager-a-auditor-b", Map.of(
            ORG_A, new OrganisationAccess(ORG_A, Set.of("manager"), Set.of(), Set.of("document_vault:view", "document_vault:publish")),
            ORG_B, new OrganisationAccess(ORG_B, Set.of("auditor"), Set.of(), Set.of("document_vault:view"))));

    @MockitoBean
    private CurrentUserAccess currentUserAccess;
    @MockitoBean
    private KeycloakSecurityHelper keycloakSecurityHelper;
    @MockitoBean
    private CeremonyService ceremonyService;
    @MockitoBean
    private KeriOobiService keriOobiService;
    @MockitoBean
    private KeriAgentService keriAgentService;
    @MockitoBean
    private KeriCredentialService keriCredentialService;
    @MockitoBean
    private KeriAuthBeginService keriAuthBeginService;
    @MockitoBean
    private KeriAttestService keriAttestService;
    @MockitoBean
    private KeriIdentityLinkRepository keriIdentityLinkRepository;

    @Autowired
    private KeriAttestationController keriAttestationController;

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
        assertAllowed(() -> keriAttestationController.getCeremony(ORG_A, "ceremony"));
        assertDenied(() -> keriAttestationController.getCeremony(ORG_B, "ceremony"));
        assertDenied(() -> keriAttestationController.attest(ORG_B, "ceremony", null));
    }

    @Test
    void query() {
        assertAllowed(() -> keriAttestationController.identity(ORG_A));
        assertDenied(() -> keriAttestationController.identity(ORG_B));
        assertDenied(() -> keriAttestationController.resetIdentity(ORG_B));
    }

    @Test
    void missingOrganisation_isRefused() {
        assertDenied(() -> keriAttestationController.agentOobi(null));
        assertDenied(() -> keriAttestationController.agentOobi(""));
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
