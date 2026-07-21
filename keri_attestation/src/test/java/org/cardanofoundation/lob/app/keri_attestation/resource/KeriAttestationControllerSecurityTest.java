package org.cardanofoundation.lob.app.keri_attestation.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.springframework.security.access.prepost.PreAuthorize;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.keri_attestation.domain.request.AuthBeginRequest;
import org.cardanofoundation.lob.app.keri_attestation.domain.request.CreateCeremonyRequest;
import org.cardanofoundation.lob.app.keri_attestation.domain.request.ResolveOobiRequest;
import org.cardanofoundation.lob.app.keri_attestation.domain.request.StepRetryRequest;

/**
 * Why reflection and not a live 403: same rationale as document_vault's
 * {@code VaultDocumentControllerSecurityTest} — method security is switched on by support's
 * {@code SecurityConfig}, which is {@code @ConditionalOnProperty(keycloak.enabled=true)}. This
 * module's tests run with Keycloak disabled, so there is no {@code securityConfig} bean and
 * {@code @PreAuthorize} is inert; a "wrong role gets 403" test here would pass regardless of what the
 * annotation said. What this DOES pin is the expression itself on every endpoint (per the brief:
 * mirror {@code VaultDocumentController#publish}'s SpEL exactly, on all of them, not just the
 * on-chain-anchoring ones).
 */
class KeriAttestationControllerSecurityTest {

    private static final String PUBLISH_ROLES =
            "hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())";

    @Test
    void identityIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("identity"));
    }

    @Test
    void agentOobiIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("agentOobi"));
    }

    @Test
    void resolveOobiIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("resolveOobi", ResolveOobiRequest.class));
    }

    @Test
    void createCeremonyIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("createCeremony", CreateCeremonyRequest.class));
    }

    @Test
    void requestCredentialIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("requestCredential", String.class,
                StepRetryRequest.class));
    }

    @Test
    void submitAuthBeginIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("submitAuthBegin", String.class,
                AuthBeginRequest.class));
    }

    @Test
    void attestIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("attest", String.class, StepRetryRequest.class));
    }

    @Test
    void getCeremonyIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("getCeremony", String.class));
    }

    private static void assertGated(java.lang.reflect.Method method) {
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertNotNull(annotation, method.getName() + " must be role-gated (manager or admin only)");
        assertEquals(PUBLISH_ROLES, annotation.value());
    }
}
