package org.cardanofoundation.lob.app.keri_attestation.domain.entity;

import java.time.Instant;
import java.time.LocalDateTime;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.domain.Persistable;

import org.hibernate.annotations.DynamicUpdate;

/**
 * One row per platform user (Keycloak subject, design §4.1). {@code bindingVersion} is incremented
 * on every relink (§4.7) so open ceremonies created under a stale binding can be invalidated. All
 * columns beyond the key/version pair are set progressively as the user completes the one-time
 * identity-level steps (OOBI resolve, credential presentation, AUTH_BEGIN) and stay {@code null}
 * until then.
 */
@Getter
@Setter
@NoArgsConstructor
@DynamicUpdate
@Entity(name = "keri_attestation.KeriIdentityLinkEntity")
@Table(name = "keri_identity_link")
public class KeriIdentityLinkEntity implements Persistable<String> {

    /** The Keycloak subject that owns this link. */
    @Id
    @NotBlank
    @Column(name = "user_id", nullable = false)
    private String userId;

    /** Incremented on every relink to a different AID (§4.7); a ceremony carries the version it was
     *  created under so a relink can invalidate ceremonies still open under the old identity. */
    @Column(name = "binding_version", nullable = false)
    private int bindingVersion;

    /** The user's Veridian AID, set once the OOBI exchange resolves. */
    @Nullable
    @Column(name = "aid")
    private String aid;

    /** The wallet OOBI URL as pasted/resolved — kept for audit and future verifier discovery (§11). */
    @Nullable
    @Column(name = "oobi_url", length = 2048)
    private String oobiUrl;

    /** SAID of the validated leaf credential, set after a successful IPEX presentation. */
    @Nullable
    @Column(name = "credential_said")
    private String credentialSaid;

    /** Schema SAID of the validated leaf credential — identifies the credential type on-chain (§4.5). */
    @Nullable
    @Column(name = "credential_schema_said")
    private String credentialSchemaSaid;

    /** Tx hash of the CONFIRMED (or verified-external) AUTH_BEGIN transaction. */
    @Nullable
    @Column(name = "auth_begin_tx_hash")
    private String authBeginTxHash;

    @Nullable
    @Column(name = "auth_begin_block")
    private Long authBeginBlock;

    @Nullable
    @Column(name = "auth_begin_at")
    private Instant authBeginAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
    public String getId() {
        return userId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
