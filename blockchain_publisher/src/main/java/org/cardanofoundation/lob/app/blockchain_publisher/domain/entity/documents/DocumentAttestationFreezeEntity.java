package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents;

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
 * Freeze record for KERI wallet-attestation of a document_vault publish (design §5.2, Task 13):
 * {@code DocumentAttestationTargetProvider#prepareDigest} does, at ATTEST time, exactly what
 * {@code DocumentL1TransactionCreator} will later do at dispatch (serialise envelope -&gt; IPFS
 * -&gt; chain tip -&gt; 1447 metadata map), and freezes the exact result here so the digest the
 * user attests equals the bytes actually published later.
 *
 * <p>IMMUTABLE per ceremony: one row per {@code (documentId, ceremonyId)} pair (enforced by a unique
 * constraint), populated once and never updated - re-attestation creates a NEW row under a new
 * ceremony id. There is deliberately no {@code updatedAt} column and no setter is ever called twice
 * on a saved instance.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity(name = "blockchain_publisher.documents.DocumentAttestationFreezeEntity")
@Table(name = "blockchain_publisher_document_attestation_freeze",
        uniqueConstraints = @UniqueConstraint(name = "uq_bp_doc_attest_freeze_doc_ceremony",
                columnNames = { "document_id", "ceremony_id" }))
public class DocumentAttestationFreezeEntity implements Persistable<String> {

    @Id
    @NotBlank
    @Column(name = "id", nullable = false)
    private String id;

    /** The document_vault document id (not a {@code blockchain_publisher} row - see class javadoc). */
    @NotBlank
    @Column(name = "document_id", nullable = false)
    private String documentId;

    @NotBlank
    @Column(name = "ceremony_id", nullable = false)
    private String ceremonyId;

    @NotBlank
    @Column(name = "ipfs_cid", nullable = false)
    private String ipfsCid;

    /** {@code CborSerializationUtil.serialize(metadataMap.getMap())} - the exact bytes dispatch will
     *  reuse verbatim once the ceremony is consumed (design §5.3, later task). */
    @NotNull
    @Column(name = "frozen_metadata_cbor", nullable = false)
    private byte[] frozenMetadataCbor;

    /** The CESR Blake3-256 digest of {@link #frozenMetadataCbor} - equals the on-chain {@code 170.d}
     *  once the wallet anchors it. */
    @NotBlank
    @Column(name = "digest_qb64", nullable = false)
    private String digestQb64;

    /** Chain tip absolute slot at freeze time, embedded in the frozen 1447 {@code metadata.creation_slot}. */
    @Column(name = "metadata_creation_slot", nullable = false)
    private long metadataCreationSlot;

    /** SHA-256 (hex) over the exact serialized envelope bytes uploaded to IPFS - the snapshot
     *  fingerprint a later publish-time check compares against to detect drift (design §5.2). */
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
