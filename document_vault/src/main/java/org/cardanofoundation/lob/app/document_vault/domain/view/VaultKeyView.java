package org.cardanofoundation.lob.app.document_vault.domain.view;

import java.time.LocalDateTime;

import jakarta.annotation.Nullable;

import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.CredentialAttestationView;

/**
 * An ORGANISATION key — one belonging to a Keycloak user who owns the private half. Used by
 * {@code /keys/me}, the org key-management listing, and card import.
 *
 * No e-mail: the holder is a Keycloak account, and the account is already the contact. Contacts who are
 * not accounts live in the addressbook and have an {@link AddressbookEntryView} instead.
 *
 * @param accountName the holder's Keycloak username, snapshotted at registration. Display only — this
 *                    platform cannot resolve a username from a sub, so it is never refreshed and may be
 *                    stale if the user renamed themselves.
 * @param origin      SELF_ENROLLED, or INDEXER_ISSUED for a card the holder imported about themselves.
 *                    Kept alongside {@code assurance} because neither implies the other.
 */
public record VaultKeyView(String keyId,
                           String organisationId,
                           String accountId,
                           String accountName,
                           String label,
                           String publicKey,
                           String credentialId,
                           KeyAssurance assurance,
                           KeyOrigin origin,
                           LocalDateTime createdAt,
                           @Nullable CredentialAttestationView attestation) {
}
