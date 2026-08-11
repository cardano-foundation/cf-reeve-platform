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

    /** Free text, and optional: two contacts may share a name — only (organisation, publicKey) is
     *  unique — and the card issuer omits a blank name rather than sending an empty one. */
    @Nullable
    @Column(name = "display_name")
    private String displayName;

    /**
     * The only contact detail there is for someone with no Reeve account. Nullable: a hand-entered
     * contact may not have one. Internal only — must never be exported to IPFS or L1.
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

    /**
     * Provenance of a Veridian attestation the importing card carried. Set once, from
     * {@code KeyCardDto.CardAttestation}, when such a card first creates this contact, and null for
     * an unattested one. Like {@link #homeOrganisationId} and {@link #assurance}, never rewritten by
     * a re-import: a later card must not retroactively attest a contact a sender already judged.
     */
    @Nullable
    @Column(name = "attestation_oobi")
    private String attestationOobi;

    @Nullable
    @Column(name = "attestation_aid")
    private String attestationAid;

    @Nullable
    @Column(name = "attestation_credential_said")
    private String attestationCredentialSaid;

    @Nullable
    @Column(name = "attestation_schema_said")
    private String attestationSchemaSaid;

    /** The KEL interaction event the attesting wallet sealed this card's attestation in — the
     *  attestation itself, since the issuing ceremony publishes nothing on-chain. */
    @Nullable
    @Column(name = "attestation_kel_sequence", length = 32)
    private String attestationKelSequence;

    @Nullable
    @Column(name = "attestation_kel_event_said", length = 255)
    private String attestationKelEventSaid;

    /** The CIP-170 label the ceremony ran under, as the exact string that went into the signed
     *  payload — an input to the anchored SAID, so it cannot be assumed on re-verification. */
    @Nullable
    @Column(name = "attestation_metadata_label", length = 32)
    private String attestationMetadataLabel;

    @Nullable
    @Column(name = "attestation_card_digest", length = 255)
    private String attestationCardDigest;

    @Nullable
    @Column(name = "attestation_payload_said", length = 255)
    private String attestationPayloadSaid;

    /** The full CESR credential chain the wallet presented, carried because the credential cannot be
     *  fetched through the OOBI alone. Nullable, and potentially large. */
    @Nullable
    @Column(name = "attestation_credential_cesr", columnDefinition = "text")
    private String attestationCredentialCesr;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
