package org.cardanofoundation.lob.app.document_vault.domain.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import org.springframework.data.domain.Persistable;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;

@Getter
@Setter
@NoArgsConstructor
@Entity(name = "document_vault.VaultDocumentEntity")
@Table(name = "document_vault_document")
public class VaultDocumentEntity extends VaultBaseEntity implements Persistable<String> {

    @Id
    @Column(name = "document_id", nullable = false)
    private String id;

    @NotBlank
    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    /** DRAFT until publish is requested; PUBLISHED locks the document forever (no edit/delete/purge). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private VaultDocumentStatus status = VaultDocumentStatus.DRAFT;

    /** Envelope wire-format version. Only version 1 is accepted today. */
    @Column(name = "envelope_version", nullable = false)
    private int envelopeVersion;

    /** SHA-256 of the ciphertext, computed server-side (content address). */
    @NotBlank
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** Client-supplied SHA-256 commitment over the plaintext — opaque here, consumed by the (future) verifying side. */
    @NotBlank
    @Column(name = "plaintext_hash", nullable = false, length = 64)
    private String plaintextHash;

    @ToString.Exclude
    @Column(name = "ciphertext", nullable = false)
    private byte[] ciphertext;

    @NotBlank
    @Column(name = "payload_nonce", nullable = false, length = 24)
    private String payloadNonce;

    @Nullable
    @Column(name = "file_name")
    private String fileName;

    @Nullable
    @Column(name = "content_type")
    private String contentType;

    @Nullable
    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @NotBlank
    @Column(name = "created_by_account", nullable = false)
    private String createdByAccount;

    @Nullable
    @Column(name = "created_by_name")
    private String createdByName;

    @Nullable
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /** Chain progress, updated by DocumentLedgerUpdateHandler from LedgerUpdatedEvent (publisher). */
    @Enumerated(EnumType.STRING)
    @Column(name = "ledger_dispatch_status", nullable = false)
    private LedgerDispatchStatus ledgerDispatchStatus = LedgerDispatchStatus.NOT_DISPATCHED;

    /**
     * Fairness cursor for {@code DocumentDispatchRetryJob}'s sweep. Null until that job re-emits this
     * document's publish command, at which point it is stamped with the sweep time. The sweep orders
     * on this column with NULLS FIRST, so never-attempted documents sort first and attempted rows
     * rotate to the back regardless of whether {@link #ledgerDispatchStatus} advances.
     */
    @Nullable
    @Column(name = "dispatch_retry_at")
    private LocalDateTime dispatchRetryAt;

    @Nullable
    @Column(name = "ledger_dispatch_error", length = 1024)
    private String ledgerDispatchError;

    @Nullable
    @Column(name = "tx_hash")
    private String txHash;

    @Nullable
    @Column(name = "ipfs_cid")
    private String ipfsCid;

    /**
     * The KERI wallet-attestation ceremony consumed by {@code VaultDocumentService#publish}, set only
     * once the freshness guard and ceremony consumption have both passed. Null for a plain publish.
     */
    @Nullable
    @Column(name = "attestation_ceremony_id", length = 64)
    private String attestationCeremonyId;

    /**
     * The consumed wallet attestation, captured at publish time so it can ride on
     * {@code DocumentPublishCommand} to the publisher tier.
     *
     * <p>The publisher used to read this back out of keri_attestation itself. It no longer depends on
     * that module — and in the split deployment it runs in a different process entirely — so the three
     * values it needs to build the on-chain CIP-170 ATTEST map are recorded here instead. All NULL for
     * a plain (unattested) publish, which is the default.
     */
    @Column(name = "attestation_aid", length = 128)
    private String attestationAid;

    /** SAID of the payload the wallet's KEL anchors; becomes the on-chain {@code 170.d}. */
    @Column(name = "attestation_payload_said", length = 128)
    private String attestationPayloadSaid;

    @Column(name = "attestation_kel_sequence", length = 32)
    private String attestationKelSequence;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "document_vault_document_slot", joinColumns = @JoinColumn(name = "document_id"))
    @OrderColumn(name = "slot_index")
    private List<DocumentSlot> slots = new ArrayList<>();

    @Override
    public boolean isNew() {
        return isNew;
    }
}
