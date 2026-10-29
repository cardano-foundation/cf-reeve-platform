package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.publish.PublishableEntity;
import org.cardanofoundation.lob.app.support.spring_audit.CommonDateOnlyLockableEntity;

/**
 * Publisher-side projection of a vault document, built exclusively from
 * {@link org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand}: no
 * lookups back into vault tables, and no e-mails, key ids, file names, account ids or labels.
 *
 * <p>Deliberately not {@code @Audited}, unlike the other publishable entities — the ciphertext must
 * never get an {@code _aud} history copy.
 */
@Getter
@Setter
@Entity(name = "blockchain_publisher.documents.DocumentEntity")
@Table(name = "blockchain_publisher_document")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners({ AuditingEntityListener.class })
@Access(AccessType.FIELD)
public class DocumentEntity extends CommonDateOnlyLockableEntity implements Persistable<String>, PublishableEntity {

    @Id
    @Column(name = "document_id", nullable = false)
    private String id;

    @NotBlank
    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    @Column(name = "envelope_version", nullable = false)
    private int envelopeVersion;

    @NotBlank
    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @NotBlank
    @Column(name = "plaintext_hash", nullable = false)
    private String plaintextHash;

    @NotBlank
    @Column(name = "payload_nonce", nullable = false)
    private String payloadNonce;

    /** Base64-encoded ciphertext. Excluded from {@code toString()} so it cannot leak into logs. */
    @NotBlank
    @ToString.Exclude
    @Column(name = "ciphertext_base64", nullable = false, columnDefinition = "TEXT")
    private String ciphertextBase64;

    /** Set once the envelope is pinned to IPFS at dispatch time; null until the first successful
     *  dispatch attempt. */
    @Nullable
    @Column(name = "ipfs_cid")
    private String ipfsCid;

    /** The KERI wallet-attestation ceremony document_vault consumed for this publish, or null for a
     *  plain publish. */
    @Nullable
    @Column(name = "attestation_ceremony_id", length = 64)
    private String attestationCeremonyId;

    /**
     * The consumed wallet attestation, carried here on the publish command rather than looked up: this
     * module depends on neither {@code keri_attestation} nor {@code document_vault}, which may run in
     * a different process. Null for a plain publish, along with the two fields below.
     */
    @Column(name = "attestation_aid", length = 128)
    private String attestationAid;

    /** SAID of the payload the wallet's KEL anchored; becomes the on-chain {@code 170.d}. */
    @Column(name = "attestation_payload_said", length = 128)
    private String attestationPayloadSaid;

    @Column(name = "attestation_kel_sequence", length = 32)
    private String attestationKelSequence;

    /** Recipient slots: crypto material only, no identifiers. */
    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "blockchain_publisher_document_slot", joinColumns = @JoinColumn(name = "document_id"))
    @OrderColumn(name = "slot_index")
    private List<Slot> slots = new ArrayList<>();

    @Nullable
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "transactionHash", column = @Column(name = "l1_transaction_hash")),
            @AttributeOverride(name = "absoluteSlot", column = @Column(name = "l1_absolute_slot")),
            @AttributeOverride(name = "creationSlot", column = @Column(name = "l1_creation_slot")),
            @AttributeOverride(name = "finalityScore", column = @Column(name = "l1_finality_score")),
            @AttributeOverride(name = "publishStatus", column = @Column(name = "l1_publish_status")),
            @AttributeOverride(name = "publishStatusErrorReason", column = @Column(name = "l1_publish_status_error_reason")),
            @AttributeOverride(name = "publishRetry", column = @Column(name = "l1_publish_retry"))
    })
    private L1SubmissionData l1SubmissionData;

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (obj instanceof DocumentEntity de) {
            return id.equals(de.getId());
        }

        return false;
    }

    public Optional<L1SubmissionData> getL1SubmissionData() {
        return Optional.ofNullable(l1SubmissionData);
    }

    public void setL1SubmissionData(Optional<L1SubmissionData> l1SubmissionData) {
        this.l1SubmissionData = l1SubmissionData.orElse(null);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getOrganisationId() {
        return organisationId;
    }

    /** One recipient slot's crypto material. */
    @Getter
    @Setter
    @Embeddable
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Slot {

        @Column(name = "ephemeral_pub", nullable = false)
        private String ephemeralPub;

        @Column(name = "wrapped_dek", nullable = false)
        private String wrappedDek;

        /**
         * sha256 of the recipient's X25519 public key, lowercase hex — the one identifier this class
         * carries, because the manifest publishes it (docs/onChainFormat.md). Derived and frozen in
         * document_vault; the publisher only relays it.
         */
        @Column(name = "recipient_key_hash", nullable = false)
        private String recipientKeyHash;

    }

}
