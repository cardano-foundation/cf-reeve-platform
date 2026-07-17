package org.cardanofoundation.lob.app.document_vault.domain.entity;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.domain.Persistable;

import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;

/**
 * An ADDRESSBOOK entry: a public key somebody gave you, so you can encrypt to them.
 *
 * A contact, not an account. Nobody logs in as one — which is why there is no account id here, and why
 * {@link #id} is the only handle. External holders read published documents in the Indexer rather than
 * signing into Reeve, so they never needed one.
 *
 * That absence is load-bearing. While imported keys shared a table with {@link VaultKeyEntity}, an
 * unsigned card could name a colleague's Keycloak sub and quietly become a recipient of everything sent
 * to them. Separate tables make the claim unrepresentable rather than merely rejected.
 *
 * Nothing here is verified. A card is unsigned and self-asserted, and a hand-typed entry is just typing;
 * the sender is expected to confirm a recipient's key out-of-band before encrypting to it
 * (trust-on-first-use). {@link #assurance} and {@link #homeOrganisationId} are shown to them precisely
 * because that judgement is theirs to make.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity(name = "document_vault.AddressbookEntryEntity")
@Table(name = "document_vault_addressbook_entry")
public class AddressbookEntryEntity extends VaultBaseEntity implements Persistable<String> {

    @Id
    @Column(name = "entry_id", nullable = false)
    private String id;

    /** Whose addressbook this contact is in. Taken from the request, authorised against the caller's JWT. */
    @NotBlank
    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    /** Free text. Two contacts may share a name — only (organisation, publicKey) is unique. */
    @NotBlank
    @Column(name = "display_name", nullable = false)
    private String displayName;

    /**
     * The only contact detail there is for someone with no Reeve account. Nullable: a hand-entered
     * contact may not have one. Internal only — must NEVER be exported to IPFS or L1 (spec B5 #3).
     */
    @Nullable
    @Column(name = "email", length = 320)
    private String email;

    @Nullable
    @Column(name = "description")
    private String description;

    /** X25519 public key, 32 bytes lowercase hex. Public material — never a secret. */
    @NotBlank
    @Column(name = "public_key", nullable = false, length = 64)
    private String publicKey;

    /**
     * Custody tier, or NULL when unknown — the honest answer for a hand-entered contact, since the
     * backend cannot know how a stranger's key was born. Only a card claims a tier, and even then it is
     * self-asserted. Must render as "unknown" rather than defaulting to a claim nobody made.
     */
    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "assurance", length = 20)
    private KeyAssurance assurance;

    /**
     * The holder's own organisation as claimed by their card (e.g. "Privat"). Free-form, unverified
     * provenance, shown to senders picking a recipient. NEVER compared against {@link #organisationId}:
     * they are unrelated, and comparing them is what once made externally-issued cards unimportable.
     */
    @Nullable
    @Column(name = "home_organisation_id")
    private String homeOrganisationId;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
