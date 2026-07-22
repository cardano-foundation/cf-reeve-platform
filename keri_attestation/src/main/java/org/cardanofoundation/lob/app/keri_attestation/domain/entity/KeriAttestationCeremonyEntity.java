package org.cardanofoundation.lob.app.keri_attestation.domain.entity;

import java.time.LocalDateTime;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.domain.Persistable;

import org.hibernate.annotations.DynamicUpdate;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;

/**
 * A single attestation ceremony: one user attesting one target (design §4.1/§4.2). {@code id} is a
 * caller-assigned UUID. There is deliberately no {@code @Version} column — concurrent step
 * completions CAS on {@code (state, attemptGeneration)} explicitly at the service layer instead of
 * relying on JPA optimistic locking, since a retry legitimately re-enters the same state with a
 * bumped generation rather than failing outright.
 */
@Getter
@Setter
@NoArgsConstructor
@DynamicUpdate
@Entity(name = "keri_attestation.KeriAttestationCeremonyEntity")
@Table(name = "keri_attestation_ceremony")
public class KeriAttestationCeremonyEntity implements Persistable<String> {

    @Id
    @NotBlank
    @Column(name = "id", nullable = false)
    private String id;

    /** Owning Keycloak subject — matches {@link KeriIdentityLinkEntity#getUserId()}. */
    @NotBlank
    @Column(name = "user_id", nullable = false)
    private String userId;

    /** The identity link version this ceremony was created under; a relink invalidates it (§4.7). */
    @Column(name = "binding_version", nullable = false)
    private int bindingVersion;

    /** e.g. {@code "DOCUMENT"} — identifies which {@code AttestationTargetProvider} owns this ceremony. */
    @NotBlank
    @Column(name = "target_type", nullable = false, length = 64)
    private String targetType;

    @NotBlank
    @Column(name = "target_id", nullable = false)
    private String targetId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private CeremonyState state;

    /** Bumped on every retry; async step completions CAS on (state, attemptGeneration) so a
     *  superseded worker's late completion is discarded rather than applied. */
    @Column(name = "attempt_generation", nullable = false)
    private int attemptGeneration;

    @Nullable
    @Column(name = "error_title")
    private String errorTitle;

    @Nullable
    @Column(name = "error_detail", length = 1024)
    private String errorDetail;

    /** SAID of the last sent exchange (IPEX apply / remotesign req), used to correlate the matching
     *  wallet notification and to detect late-arriving replies before a retry re-sends. */
    @Nullable
    @Column(name = "request_exn_said")
    private String requestExnSaid;

    /** The CESR digest of the raw label-{@link #metadataLabel} metadata value (e.g. the 1447 document
     *  metadata) — used for freeze/digest matching only ({@code DocumentAttestationLookup}). <b>No
     *  longer equals the on-chain {@code 170.d}</b> (design §4.4 rev 3, user-directed 2026-07-22 after
     *  live wallet testing): Veridian's remotesign flow anchors the SAID of the whole
     *  {@link RemotesignRequestFactory}-built request payload, not this raw digest directly — see
     *  {@link #payloadSaid}, which IS the on-chain {@code 170.d}. */
    @Nullable
    @Column(name = "metadata_digest")
    private String metadataDigest;

    @Nullable
    @Column(name = "metadata_label")
    private String metadataLabel;

    /** SAID of the saidified remotesign request payload {@code {i, d, metadataLabel, metadataDigest}}
     *  sent to the wallet ({@link RemotesignRequestFactory#anchorRequestKed}) — the value the wallet's
     *  KEL interaction-event seal is expected to equal, and therefore the on-chain {@code 170.d} once
     *  ATTEST_ANCHORED (design §4.4 rev 3). Persisted before the request is sent, alongside
     *  {@link #requestExnSaid}. Distinct from {@link #metadataDigest} (the raw 1447/freeze digest) and
     *  from {@link #kelEventSaid} (the anchoring KEL event's OWN SAID, i.e. its {@code d} — not the
     *  SAID inside its seal). */
    @Nullable
    @Column(name = "payload_said")
    private String payloadSaid;

    /** KEL sequence (hex) of the anchoring event — equals the on-chain {@code 170.s}. */
    @Nullable
    @Column(name = "kel_sequence", length = 64)
    private String kelSequence;

    /** SAID of the anchoring KEL event, kept for audit alongside {@link #kelSequence}. */
    @Nullable
    @Column(name = "kel_event_said")
    private String kelEventSaid;

    /** F1 fix, hardened by R1 (Codex re-verification): the KERI AID that actually attested this
     *  ceremony — the wallet AID the remotesign request was sent to and answered by, written
     *  immutably by {@code KeriAttestService#resolveAndComplete} alongside {@link #kelSequence}/{@link
     *  #kelEventSaid} at the moment this ceremony reaches {@code ATTEST_ANCHORED}, i.e. BEFORE it can
     *  ever reach {@code CONSUMED}. Read (never re-derived) by {@code CeremonyService
     *  #validateAndConsume}/{@code CeremonyService#findConsumed} to build {@code
     *  ConsumedAttestation.aid}, so a consume that races a relink of the SAME user can never emit a
     *  CIP-170 attestation under the new (post-relink) AID while still carrying the digest/kelSequence
     *  anchored under the original one. {@code null} on a {@code CONSUMED} row is not a state this
     *  module can produce — {@code validateAndConsume} fails closed (CEREMONY_INVALID_STATE) rather
     *  than transition to {@code CONSUMED} without it — so a null here indicates data corruption, not a
     *  legitimate case to recover from; this module has never been deployed, so no pre-upgrade row of
     *  that shape exists in any real database. */
    @Nullable
    @Column(name = "attester_aid")
    private String attesterAid;

    /** F5 fix: the wallet's KEL sequence (hex) at ATTEST-request time, queried and persisted BEFORE the
     *  remotesign request is sent. Any candidate/fallback anchoring event {@code awaitAnchor} accepts
     *  must have a sequence at or after this floor, so an old KEL event that happens to carry the same
     *  {@link #metadataDigest} (e.g. left over from a prior attestation of identical content) can never
     *  satisfy a fresh request. */
    @Nullable
    @Column(name = "kel_floor_sequence", length = 64)
    private String kelFloorSequence;

    /** Pending AUTH_BEGIN tx hash while this ceremony is {@code AUTH_BEGIN_SUBMITTED} (Task 9). The
     *  CONFIRMED hash is persisted separately to {@code KeriIdentityLinkEntity#authBeginTxHash} (one
     *  per identity, not per ceremony) once {@code KeriAuthBeginService#awaitAuthBeginConfirmation}
     *  observes enough confirmations. */
    @Nullable
    @Column(name = "auth_begin_tx_hash")
    private String authBeginTxHash;

    /** F8 fix: which half of the two-phase CREDENTIAL_REQUESTED wait (apply/offer, then agree/grant) a
     *  retry last reached — {@code APPLY_SENT} or {@code AGREE_SENT} (see
     *  {@code KeriCredentialService}'s constants of the same names). {@code null} means no phase
     *  recorded yet, equivalent to {@code APPLY_SENT} for retry purposes. Cleared on step
     *  completion/failure so a stale value never lingers once this ceremony's CREDENTIAL_REQUESTED step
     *  is done. */
    @Nullable
    @Column(name = "step_phase", length = 32)
    private String stepPhase;

    /** cip113 wire-flow parity (design rev, user-directed 2026-07-22, {@code KeriService
     *  #presentCredential}): the IPEX AGREE exchange's own {@code atc} (attachment) — not the ADMIT's
     *  own — is what {@code submitAdmit} must be given, a proven cip113 wallet-contract quirk this
     *  module now matches exactly. Persisted alongside {@link #requestExnSaid} at the {@code AGREE_SENT}
     *  phase transition so a worker restart resuming at that phase (F8 fix) can still supply it, rather
     *  than only being available from the in-memory {@code ExchangeMessageResult} of the attempt that
     *  originally built the agree. */
    @Nullable
    @Column(name = "agree_atc")
    private String agreeAtc;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** TTL deadline (from {@code ceremony-ttl} config), set by the caller at creation. */
    @NotNull
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Transient
    private boolean isNew = true;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
