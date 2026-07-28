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
 * A single attestation ceremony: one user attesting one target. {@code id} is a
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

    /** The identity link version this ceremony was created under; a relink invalidates it. */
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
     *  metadata) — used for freeze and digest matching only. <b>Not the on-chain {@code 170.d}</b>:
     *  Veridian's remotesign flow anchors the SAID of the whole
     *  {@link RemotesignRequestFactory}-built request payload rather than this raw digest. See
     *  {@link #payloadSaid}, which is the on-chain {@code 170.d}. */
    @Nullable
    @Column(name = "metadata_digest")
    private String metadataDigest;

    @Nullable
    @Column(name = "metadata_label")
    private String metadataLabel;

    /** SAID of the saidified remotesign request payload {@code {i, d, metadataLabel, metadataDigest}}
     *  sent to the wallet ({@link RemotesignRequestFactory#anchorRequestKed}) — the value the wallet's
     *  KEL interaction-event seal is expected to equal, and therefore the on-chain {@code 170.d} once
     *  ATTEST_ANCHORED. Persisted before the request is sent, alongside
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

    /** The KERI AID that actually attested this
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

    /** The wallet's KEL sequence (hex) at ATTEST-request time, queried and persisted before the
     *  remotesign request is sent. Any candidate/fallback anchoring event {@code KeriAttestService
     *  #resolveAndComplete} accepts must have a sequence at or after this floor, so an old KEL event
     *  that happens to carry the same {@link #metadataDigest} (e.g. left over from a prior attestation
     *  of identical content) can never satisfy a fresh request. */
    @Nullable
    @Column(name = "kel_floor_sequence", length = 64)
    private String kelFloorSequence;

    /** DEAD COLUMN (synchronous refactor, design rev user-directed): originally the pending AUTH_BEGIN
     *  tx hash while this ceremony sat at {@code AUTH_BEGIN_SUBMITTED} across
     *  multiple requests, read back by the now-removed {@code KeriAuthBeginService
     *  #awaitAuthBeginConfirmation} background poll. {@code KeriAuthBeginService#submitAuthBegin} now
     *  completes the step synchronously, in the same call that submits the tx — the hash goes straight
     *  from a local variable into {@code KeriIdentityLinkEntity#authBeginTxHash} without ever needing to
     *  round-trip through this column. No longer written. Column intentionally left in place (harmless)
     *  rather than dropped, per the migration-safety note on {@link #stepPhase}. */
    @Nullable
    @Column(name = "auth_begin_tx_hash")
    private String authBeginTxHash;

    /** DEAD COLUMN (synchronous refactor, design rev user-directed): originally recorded which half of
     *  the two-phase CREDENTIAL_REQUESTED wait (apply/offer, then agree/grant) a retry last reached, so
     *  a background worker resuming on a LATER request could skip re-sending the apply or the agree.
     *  {@code KeriCredentialService#presentCredential} now runs the entire apply→offer→agree→grant→admit
     *  round trip on the original request thread in one call — there is no longer a separate request to
     *  resume mid-step into, so no phase needs recording. A crash mid-flight simply abandons the whole
     *  request; a subsequent retry re-enters from {@code beginStep} and, at most, re-checks for a
     *  late-arriving offer before re-sending. No longer written. Column intentionally left in place
     *  (harmless) rather than dropped: dropping a column is a separate, independently-riskier migration
     *  than simply retiring the code that wrote it. */
    @Nullable
    @Column(name = "step_phase", length = 32)
    private String stepPhase;

    /** DEAD COLUMN (synchronous refactor, design rev user-directed): originally persisted the IPEX
     *  AGREE exchange's own {@code atc} (attachment) — not the ADMIT's own, a proven wallet-contract
     *  quirk {@code submitAdmit} must still honor — alongside {@link
     *  #requestExnSaid} at the (now-removed) {@code AGREE_SENT} phase transition, so a worker restart
     *  resuming at that phase on a LATER request could still supply it. {@code
     *  KeriCredentialService#presentCredential} now holds the agree's {@code atc} as a local variable for
     *  the lifetime of the one synchronous call that needs it (build agree → ... → submit admit), so it
     *  never needs to survive past that call. No longer written; see {@link #stepPhase}'s javadoc for why
     *  the column stays. */
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
