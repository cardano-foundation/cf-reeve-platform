package org.cardanofoundation.lob.app.document_vault.domain;

import jakarta.annotation.Nullable;

import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;

/**
 * A public key a document slot can wrap to, from either store, reduced to what the document paths
 * actually need. Produced by {@code VaultKeyLookupService}.
 *
 * The point of this type is that upload validation, fetch authorisation, the envelope's recipient list
 * and the publish fan-out do not care whether a slot names a colleague's key or an addressbook contact —
 * they ask the same four questions of both. It exists because a foreign key cannot reference two tables.
 *
 * @param id             the org key id or the addressbook entry id — whichever a slot names.
 * @param organisationId which organisation the row belongs to. Checked on upload.
 * @param accountId      the holder's Keycloak sub, or NULL for an addressbook contact.
 *                       <p>
 *                       Null is meaningful, not missing. A contact has no Reeve account, so nothing can
 *                       match them: they cannot fetch a document through the API, and they cannot be
 *                       notified in-app. Both fall out of this field being null, which is correct —
 *                       external holders read published documents in the Indexer instead. Callers must
 *                       not paper over it with a placeholder.
 * @param publicKey      X25519 public key, 32 bytes lowercase hex.
 * @param displayName    the Keycloak username, or the contact's name.
 * @param label          the key's label, or the contact's description.
 * @param assurance      custody tier, or NULL when unknown — a hand-entered contact claims none.
 */
public record KeyRef(String id,
                     String organisationId,
                     @Nullable String accountId,
                     String publicKey,
                     @Nullable String displayName,
                     @Nullable String label,
                     @Nullable KeyAssurance assurance) {

    public static KeyRef ofOrgKey(String id, String organisationId, String accountId, String publicKey,
                                  String displayName, String label, KeyAssurance assurance) {
        return new KeyRef(id, organisationId, accountId, publicKey, displayName, label, assurance);
    }

    public static KeyRef ofAddressbookEntry(String id, String organisationId, String publicKey,
                                            String displayName, String description,
                                            @Nullable KeyAssurance assurance) {
        return new KeyRef(id, organisationId, null, publicKey, displayName, description, assurance);
    }
}
