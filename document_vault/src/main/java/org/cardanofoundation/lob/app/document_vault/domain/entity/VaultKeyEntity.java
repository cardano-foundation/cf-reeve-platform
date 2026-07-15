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

@Getter
@Setter
@NoArgsConstructor
@Entity(name = "document_vault.VaultKeyEntity")
@Table(name = "document_vault_key")
public class VaultKeyEntity extends VaultBaseEntity implements Persistable<String> {

    @Id
    @Column(name = "key_id", nullable = false)
    private String id;

    @NotBlank
    @Column(name = "account_id", nullable = false)
    private String accountId;

    /** Exactly ONE organisation per key entry (product decision). Immutable after registration. */
    @NotBlank
    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    @Nullable
    @Column(name = "account_name")
    private String accountName;

    /** Notification address (addressbook). Internal only — must NEVER be exported to IPFS or L1 (spec B5 #3). */
    @NotBlank
    @Column(name = "email", nullable = false, length = 320)
    private String email;

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

    /** True when the holder has no Reeve login (they read published documents in the Indexer instead). */
    @Column(name = "external", nullable = false)
    private boolean external;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
