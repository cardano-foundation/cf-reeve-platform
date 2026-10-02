package org.cardanofoundation.lob.app.keri_attestation.domain.entity;

import java.time.Instant;

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

import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchema.TrustModel;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.AttestationStatus;

/**
 * What this deployment concluded about one imported card's credential, and why.
 *
 * <p>Owned by {@code keri_attestation} rather than stored as more columns on the vault's key and
 * addressbook rows. Verification is this module's decision, made under this module's policy, and a
 * verdict living next to the data it judges tends to drift out of step with the policy that produced
 * it. Consumers read it through {@code CredentialVerificationApi} and never join to this table.
 *
 * <p>Keyed by the card itself — organisation plus public key — not by a vault row id. Both the key
 * table and the addressbook table can hold the same card, the verdict is the same either way, and
 * keying on a vault row id would make this module depend on identifiers it does not own.
 *
 * <p>{@link #policyFingerprint} records which policy produced the verdict, so a later configuration
 * change is reconstructable rather than silently rewriting history: a row verified under yesterday's
 * trusted roots is visibly not the same claim as one verified under today's.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity(name = "keri_attestation.CredentialVerificationEntity")
@Table(name = "keri_attestation_credential_verification")
public class CredentialVerificationEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @NotBlank
    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    /** The card's X25519 public key — what identifies the subject this verdict is about. */
    @NotBlank
    @Column(name = "public_key", nullable = false, length = 64)
    private String publicKey;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AttestationStatus status;

    /** When the checks actually ran. The badge says "verified at import" and means this instant. */
    @NotNull
    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    /** The AID that attested the card and anchored it in its own KEL. */
    @Nullable
    @Column(name = "attester_aid", length = 255)
    private String attesterAid;

    @Nullable
    @Column(name = "credential_said", length = 255)
    private String credentialSaid;

    @Nullable
    @Column(name = "schema_said", length = 255)
    private String schemaSaid;

    /** The schema's configured display name, snapshotted so the UI reads the name in force at the time. */
    @Nullable
    @Column(name = "schema_name", length = 255)
    private String schemaName;

    /** Who issued the credential. In a vLEI chain this is the QVI, not the anchor. */
    @Nullable
    @Column(name = "leaf_issuer_aid", length = 255)
    private String leafIssuerAid;

    /** What trust was established against: the chain root for CHAINED, the issuer for STANDALONE. */
    @Nullable
    @Column(name = "trust_anchor_aid", length = 255)
    private String trustAnchorAid;

    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "trust_model", length = 20)
    private TrustModel trustModel;

    @Nullable
    @Column(name = "kel_sequence", length = 32)
    private String kelSequence;

    @Nullable
    @Column(name = "kel_event_said", length = 255)
    private String kelEventSaid;

    /** Digest of the schema entry that produced this verdict — see the class javadoc. */
    @Nullable
    @Column(name = "policy_fingerprint", length = 64)
    private String policyFingerprint;

    /** The credential's own attributes, as JSON. Display only; never a trust input. */
    @Nullable
    @Column(name = "claims", columnDefinition = "text")
    private String claims;
}
