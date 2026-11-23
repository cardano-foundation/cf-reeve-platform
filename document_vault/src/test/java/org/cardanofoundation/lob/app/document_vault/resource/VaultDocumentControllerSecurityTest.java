package org.cardanofoundation.lob.app.document_vault.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.springframework.security.access.prepost.PreAuthorize;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.domain.request.PublishDocumentRequest;

/**
 * Module tests run without method security, so a live 403 test here would pass whatever the annotation said.
 * What can regress is the expression itself (someone widening it), and that is what this pins.
 */
class VaultDocumentControllerSecurityTest {

    @Test
    void publishNeedsThePublishPermission() throws NoSuchMethodException {
        PreAuthorize annotation = VaultDocumentController.class
                .getMethod("publish", String.class, String.class, PublishDocumentRequest.class)
                .getAnnotation(PreAuthorize.class);

        assertNotNull(annotation, "publish must be permission-gated: anchoring on-chain is irreversible");
        assertEquals("@permissions.can(#organisationId, 'document_vault', 'publish')", annotation.value());
    }
}
