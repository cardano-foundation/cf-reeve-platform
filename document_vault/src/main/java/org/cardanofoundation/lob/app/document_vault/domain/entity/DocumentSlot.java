package org.cardanofoundation.lob.app.document_vault.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One recipient slot of an envelope. {@code keyId}/{@code recipientRef} are labels and indexing
 * aids only — never trust anchors (blueprint I6). {@code wrappedDek} is AES-256-GCM-encrypted
 * under an ECDH-derived slot KEK; the server cannot unwrap it (blueprint I5).
 *
 * <p>{@code recipientKeyHash} is the odd one out: it is DERIVED server-side from the recipient's
 * stored public key — never accepted from a client, which could otherwise stamp someone else's
 * identifier onto a document — and, unlike every other identifier here, it IS exported to L1. It is
 * frozen at upload so the attested publish path's wallet-signed metadata cannot drift when
 * {@code DocumentDispatchRetryJob} re-derives the publish command on a retry sweep.
 */
@Getter
@Setter
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
public class DocumentSlot {

    @Column(name = "key_id", nullable = false)
    private String keyId;

    @Column(name = "recipient_ref", nullable = false)
    private String recipientRef;

    @Column(name = "ephemeral_pub", nullable = false, length = 64)
    private String ephemeralPub;

    @Column(name = "wrapped_dek", nullable = false, length = 96)
    private String wrappedDek;

    /** sha256 of the recipient's 32-byte X25519 public key, lowercase hex. Published on-chain. */
    @Column(name = "recipient_key_hash", nullable = false, length = 64)
    private String recipientKeyHash;
}
