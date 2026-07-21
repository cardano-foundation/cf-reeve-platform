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

    /** The digest handed to the wallet to anchor — equals the on-chain {@code 170.d} once ATTEST_ANCHORED. */
    @Nullable
    @Column(name = "metadata_digest")
    private String metadataDigest;

    @Nullable
    @Column(name = "metadata_label")
    private String metadataLabel;

    /** KEL sequence (hex) of the anchoring event — equals the on-chain {@code 170.s}. */
    @Nullable
    @Column(name = "kel_sequence", length = 64)
    private String kelSequence;

    /** SAID of the anchoring KEL event, kept for audit alongside {@link #kelSequence}. */
    @Nullable
    @Column(name = "kel_event_said")
    private String kelEventSaid;

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
