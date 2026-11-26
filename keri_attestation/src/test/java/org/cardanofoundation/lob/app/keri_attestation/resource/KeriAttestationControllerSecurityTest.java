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
 * Module tests run without method security, so a live 403 test here would pass whatever the annotation said.
 * What this pins is the expression on every endpoint: all of them either anchor on-chain or feed a ceremony that
 * will, so they need the same permission as publishing a document.
 */
class KeriAttestationControllerSecurityTest {

    private static final String PUBLISH = "@permissions.can(#organisationId, 'document_vault', 'publish')";

    @Test
    void identityIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("identity", String.class));
    }

    @Test
    void agentOobiIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("agentOobi", String.class));
    }

    @Test
    void resolveOobiIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("resolveOobi", String.class, ResolveOobiRequest.class));
    }

    @Test
    void resetIdentityIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("resetIdentity", String.class));
    }

    @Test
    void createCeremonyIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("createCeremony", String.class, CreateCeremonyRequest.class));
    }

    @Test
    void requestCredentialIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("requestCredential", String.class, String.class,
                StepRetryRequest.class));
    }

    @Test
    void submitAuthBeginIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("submitAuthBegin", String.class, String.class,
                AuthBeginRequest.class));
    }

    @Test
    void attestIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("attest", String.class, String.class, StepRetryRequest.class));
    }

    @Test
    void getCeremonyIsRoleGated() throws NoSuchMethodException {
        assertGated(KeriAttestationController.class.getMethod("getCeremony", String.class, String.class));
    }

    private static void assertGated(java.lang.reflect.Method method) {
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertNotNull(annotation, method.getName() + " must be permission-gated");
        assertEquals(PUBLISH, annotation.value());
    }
}
