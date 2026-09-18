package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * The authoritative per-organisation NetSuite configuration. Owned solely by this module — the
 * organisation module holds only a projection of its non-secret fields.
 */
@Entity
@Table(name = "netsuite_adapter_organisation_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class NetSuiteConfigEntity {

    @Id
    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "token_url", nullable = false)
    private String tokenUrl;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "certificate_id", nullable = false)
    private String certificateId;

    /** {@code v1:}-prefixed AES-GCM envelope. Excluded from {@link #toString()}. */
    @Column(name = "private_key_encrypted", nullable = false)
    @ToString.Exclude
    private String privateKeyEncrypted;

    /** Monotonic, assigned by the organisation module. Guards against replay and reordering. */
    @Column(name = "revision", nullable = false)
    private long revision;

    /**
     * Outcome of the last verification against NetSuite for this revision.
     * <p>
     * Persisted so a redelivered event can be acknowledged with the real verdict instead of a
     * fabricated success — otherwise a replay of a revision whose credentials were rejected
     * would flip the organisation projection to valid.
     */
    @Column(name = "validation_status", length = 16)
    private String validationStatus;

    @Column(name = "validation_message")
    private String validationMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
