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
}
