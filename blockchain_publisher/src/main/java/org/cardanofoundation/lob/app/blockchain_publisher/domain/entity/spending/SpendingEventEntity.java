package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.publish.PublishableEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.support.spring_audit.CommonDateOnlyLockableEntity;

@Getter
@Setter
@Entity(name = "blockchain_publisher.spending.SpendingEventEntity")
@Table(name = "blockchain_publisher_spending_event")
@Builder
@Audited
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners({ AuditingEntityListener.class })
@Access(AccessType.FIELD)
public class SpendingEventEntity extends CommonDateOnlyLockableEntity implements Persistable<String>, PublishableEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @NotBlank
    @Column(name = "funding_id", nullable = false)
    private String fundingId;

    @NotBlank
    @Column(name = "activity_id", nullable = false)
    private String activityId;

    @Nullable
    @Column(name = "activity_title")
    private String activityTitle;

    @Nullable
    @Column(name = "activity_sub_title")
    private String activitySubTitle;

    @Nullable
    @Column(name = "funding_tx")
    private String fundingTx;

    @Nullable
    @Column(name = "funding_doc_hash")
    private String fundingDocHash;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @Column(name = "event_date")
    private LocalDate eventDate;

    @NotNull
    @Column(name = "total_amount", nullable = false)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @NotBlank
    @Column(name = "currency", nullable = false)
    private String currency;

    @Nullable
    @Column(name = "currency_id")
    private String currencyId;

    /** Spend line items — populated only for SPENDING events. */
    @Builder.Default
    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL)
    private List<SpendingItemEntity> spendingItems = new ArrayList<>();

    /** Milestone allocations — populated only for FUNDING and REFUND events. */
    @Builder.Default
    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL)
    private List<EventMilestoneAllocationEntity> milestoneAllocations = new ArrayList<>();

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "organisation_id")),
            @AttributeOverride(name = "name", column = @Column(name = "organisation_name")),
            @AttributeOverride(name = "taxIdNumber", column = @Column(name = "organisation_tax_id_number")),
            @AttributeOverride(name = "countryCode", column = @Column(name = "organisation_country_code")),
            @AttributeOverride(name = "currencyId", column = @Column(name = "organisation_currency_id")),
    })
    private Organisation organisation;

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
        return Objects.hashCode(eventId);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (obj instanceof SpendingEventEntity te) {
            return eventId.equals(te.getId());
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
        return eventId;
    }

    @Override
    public String getOrganisationId() {
        return organisation.getId();
    }

}
