package org.cardanofoundation.lob.app.document_vault.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.springframework.security.access.prepost.PreAuthorize;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.domain.request.PublishDocumentRequest;

/**
 * Why reflection and not a live 403: method security is switched on by support's SecurityConfig,
 * which is @ConditionalOnProperty(keycloak.enabled=true). The module's tests run with Keycloak
 * DISABLED — there is no `securityConfig` bean and @PreAuthorize is inert — so an "accountant gets
 * 403" test here would pass no matter what the annotation said, which is worse than no test.
 *
 * What CAN regress is the expression itself (someone widening it to ALL_ROLES). That is what this
 * pins. End-to-end role enforcement is a deployment concern, as for every other @PreAuthorize here.
 */
class VaultDocumentControllerSecurityTest {

    @Test
    void publishIsRestrictedToManagerAndAdmin() throws NoSuchMethodException {
        PreAuthorize annotation = VaultDocumentController.class
                .getMethod("publish", String.class, PublishDocumentRequest.class)
                .getAnnotation(PreAuthorize.class);

        assertNotNull(annotation, "publish must be role-gated: anchoring on-chain is irreversible");
        assertEquals("hasRole(@securityConfig.getManagerRole()) "
                        + "or hasRole(@securityConfig.getAdminRole())",
                annotation.value());
    }
}
