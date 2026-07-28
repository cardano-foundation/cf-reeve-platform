package org.cardanofoundation.lob.app.document_vault.domain.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.domain.Persistable;

/**
 * One frozen attestation commitment, written when a ceremony reaches the attest step and read back
 * when the document is published.
 *
 * <p>Lives in document_vault because the ceremony runs in the user-facing tier. It previously lived in
 * blockchain_publisher, which the split deployment's `api` service does not load at all — so freezing
 * was impossible there and every DOCUMENT ceremony failed with TARGET_MISMATCH.
 *
 * <p>Immutable once written: there is no {@code updatedAt} and no setter is called twice on a saved
 * instance. Idempotency is enforced by the unique {@code (document_id, ceremony_id)} constraint, and a
 * caller that loses the race re-reads the winner's row rather than failing.
 *
 * <p>Unlike its predecessor it stores no {@code ipfs_cid} and no {@code metadata_creation_slot}:
 * neither is knowable on the pod that writes this row. See {@code DocumentAttestationCommitment}.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity(name = "document_vault.DocumentAttestationFreezeEntity")
@Table(name = "document_vault_attestation_freeze",
        uniqueConstraints = @UniqueConstraint(name = "uq_dv_attest_freeze_doc_ceremony",
                columnNames = { "document_id", "ceremony_id" }))
public class DocumentAttestationFreezeEntity implements Persistable<String> {

    @Id
    @NotBlank
    @Column(name = "id", nullable = false)
    private String id;

    @NotBlank
    @Column(name = "document_id", nullable = false)
    private String documentId;

    @NotBlank
    @Column(name = "ceremony_id", nullable = false)
    private String ceremonyId;

    /** CBOR of the {@code DocumentAttestationCommitment} map — the exact bytes the wallet anchors. */
    @NotNull
    @Column(name = "commitment_cbor", nullable = false)
    private byte[] commitmentCbor;

    /** CESR Blake3-256 digest of {@link #commitmentCbor}. */
    @NotBlank
    @Column(name = "digest_qb64", nullable = false)
    private String digestQb64;

    /**
     * SHA-256 (hex) of the exact envelope bytes the publisher will pin. Re-derived at publish time and
     * compared, so a document edited between freeze and publish is caught rather than published under
     * an attestation the wallet gave for different content.
     */
    @NotBlank
    @Column(name = "envelope_sha256", nullable = false, length = 64)
    private String envelopeSha256;

    @NotNull
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Transient
    private boolean isNew = true;

    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public String getId() {
        return id;
    }
}
