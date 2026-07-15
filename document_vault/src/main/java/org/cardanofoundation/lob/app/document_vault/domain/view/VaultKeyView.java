package org.cardanofoundation.lob.app.document_vault.domain.view;

import java.time.LocalDateTime;

import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;

/**
 * A key entry with its mapped user. Used by {@code /keys/me}, the org key-management listing, and
 * card import. E-mail is org-internal contact data — it never reaches IPFS or L1 (spec B5 #3).
 */
public record VaultKeyView(String keyId,
                           String organisationId,
                           String accountId,
                           String accountName,
                           String label,
                           String publicKey,
                           String email,
                           String credentialId,
                           KeyAssurance assurance,
                           KeyOrigin origin,
                           boolean external,
                           LocalDateTime createdAt) {
}
