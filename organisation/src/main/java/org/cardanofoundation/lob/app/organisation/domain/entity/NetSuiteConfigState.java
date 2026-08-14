package org.cardanofoundation.lob.app.organisation.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Organisation-owned read model of the NetSuite configuration held by the netsuite module.
 * <p>
 * Holds NO secret material — only a fingerprint of the private key. The organisation module must
 * never read the netsuite module's tables, so this projection is the only thing the status
 * endpoint reads, and it is fed exclusively by {@code NetSuiteConfigAppliedEvent}.
 * <p>
 * Deliberately does not extend {@code CommonEntity}: this is a projection, not an audited
 * aggregate, and Envers would otherwise demand an {@code _aud} table for derived state.
 */
@Entity
@Table(name = "organisation_netsuite_config_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NetSuiteConfigState {

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

    @Column(name = "private_key_fingerprint", nullable = false)
    private String privateKeyFingerprint;

    /** Whether the netsuite module received and stored the configuration. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sync_state", nullable = false)
    private NetSuiteSyncState syncState;

    @Column(name = "sync_message")
    private String syncMessage;

    @Column(name = "revision", nullable = false)
    private long revision;

    /**
     * Whether the credentials actually authenticate against NetSuite. Distinct from
     * {@link #syncState}: a configuration can be stored and still not work. Null until the
     * first acknowledgement is processed.
     */
    @Column(name = "netsuite_valid")
    private Boolean netsuiteValid;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "validation_message")
    private String validationMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

}
