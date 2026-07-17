package org.cardanofoundation.lob.app.document_vault.domain.entity;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.domain.Persistable;

import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;

/**
 * An ORGANISATION key: its holder is a Keycloak user in this organisation and owns the private half.
 * Identity comes from the login, which is why there is no e-mail here — the account is the contact.
 *
 * Not an addressbook entry. A public key someone merely handed you is an {@link AddressbookEntryEntity}:
 * a contact, not an account, in its own table. Keeping the two apart is what makes it impossible for an
 * imported card to claim a Keycloak account it cannot prove it owns.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity(name = "document_vault.VaultKeyEntity")
@Table(name = "document_vault_key")
public class VaultKeyEntity extends VaultBaseEntity implements Persistable<String> {

    @Id
    @Column(name = "key_id", nullable = false)
    private String id;

    /** The holder's Keycloak sub. Always a real account: a card about anyone else becomes an
     *  {@link AddressbookEntryEntity} instead, so no foreign identity can appear here. */
    @NotBlank
    @Column(name = "account_id", nullable = false)
    private String accountId;

    /** Exactly ONE organisation per key entry (product decision). Immutable after registration. */
    @NotBlank
    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    /**
     * The holder's Keycloak username, snapshotted from their own JWT at registration. This platform has
     * no way to resolve a username from a sub — there is no Keycloak admin client, and
     * {@code KeycloakSecurityHelper.getCurrentUser()} reads only the current token — so it cannot be
     * refreshed and goes stale if the user renames themselves. Display only; never an identity.
     */
    @Nullable
    @Column(name = "account_name")
    private String accountName;

    @Nullable
    @Column(name = "credential_id")
    private String credentialId;

    /** X25519 public key, 32 bytes lowercase hex. Public material — never a secret. */
    @NotBlank
    @Column(name = "public_key", nullable = false, length = 64)
    private String publicKey;

    @NotBlank
    @Column(name = "label", nullable = false)
    private String label;

    /** How this entry got here: passkey enrollment, or an imported key card. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20)
    private KeyOrigin origin;

    /**
     * Custody tier (blueprint I2, amended). PASSKEY = the private half never left the owner's device.
     * PORTABLE = an Indexer operator minted it and handed it over on a card, so it has existed outside
     * that device. This is PROVENANCE, not storage: wrapping a portable key under a passkey later does
     * not un-see what the operator saw, so the value NEVER upgrades. The UI must show it wherever a key
     * is chosen or a recipient picked — the honest claim differs between the two.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "assurance", nullable = false, length = 20)
    private KeyAssurance assurance;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
