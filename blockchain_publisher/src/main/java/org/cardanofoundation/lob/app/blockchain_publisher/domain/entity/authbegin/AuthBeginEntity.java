package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.authbegin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.publish.PublishableEntity;
import org.cardanofoundation.lob.app.support.spring_audit.CommonDateOnlyLockableEntity;

/**
 * A pending CIP-170 AUTH_BEGIN publication, requested by {@code keri_attestation}.
 *
 * <p>Keyed by the ceremony id, which is what the ledger update carries back so the ceremony's
 * AUTH_BEGIN step can be completed. Everything needed to rebuild the label-170 map is stored here, so
 * a dispatch retry never has to ask {@code keri_attestation} anything — the two modules may run in
 * different processes.
 *
 * <p>Carries identity material (the AID and its credential chain) on purpose: publishing exactly that
 * on-chain is what AUTH_BEGIN is for.
 */
@Getter
@Setter
@Entity(name = "blockchain_publisher.authbegin.AuthBeginEntity")
@Table(name = "blockchain_publisher_auth_begin")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners({ AuditingEntityListener.class })
@Access(AccessType.FIELD)
public class AuthBeginEntity extends CommonDateOnlyLockableEntity implements Persistable<String>, PublishableEntity {

    /** The ceremony this publication belongs to. */
    @Id
    @Column(name = "ceremony_id", nullable = false)
    private String id;

    @NotBlank
    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    /** The KERI AID whose signing authority is published. */
    @NotBlank
    @Column(name = "aid", nullable = false)
    private String aid;

    /** Schema SAID of the leaf credential; the map's {@code s} field. */
    @NotBlank
    @Column(name = "leaf_schema_said", nullable = false)
    private String leafSchemaSaid;

    /** The reduced CESR credential chain, chunked into the map's {@code c} field. */
    @Column(name = "reduced_cesr_chain", nullable = false)
    private byte[] reducedCesrChain;

    /** Metadata labels this AID is authorised for; the map's {@code m.l} list. */
    @Column(name = "authorized_labels", nullable = false)
    private String authorizedLabels;

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

    /** Stored comma-separated so the row stays a single flat record; the list is small and fixed. */
    public List<Long> authorizedLabelsAsList() {
        if (authorizedLabels == null || authorizedLabels.isBlank()) {
            return List.of();
        }

        return List.of(authorizedLabels.split(",")).stream().map(String::trim).map(Long::valueOf).toList();
    }

    public static String encodeAuthorizedLabels(List<Long> labels) {
        return labels.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof AuthBeginEntity other) {
            return id.equals(other.getId());
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

}
