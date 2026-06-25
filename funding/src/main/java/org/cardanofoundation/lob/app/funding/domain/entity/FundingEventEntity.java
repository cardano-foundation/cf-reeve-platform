package org.cardanofoundation.lob.app.funding.domain.entity;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.TemporalType.TIMESTAMP;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.*;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@Entity(name = "funding.FundingEventEntity")
@Table(name = "funding_event")
@Audited
@EntityListeners({AuditingEntityListener.class})
public class FundingEventEntity extends CommonEntity implements Persistable<String> {

    @Id
    @Column(name = "event_id", nullable = false)
    private String id;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private EventStatus status = EventStatus.DRAFT;

    @NotBlank
    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    @NotBlank
    @Column(name = "funding_id", nullable = false)
    private String fundingId;

    @Builder.Default
    @Column(name = "ledger_dispatch_approved")
    private boolean ledgerDispatchApproved = false;

    @Builder.Default
    @Enumerated(STRING)
    private LedgerDispatchStatus ledgerDispatchStatus = LedgerDispatchStatus.NOT_DISPATCHED;

    @Temporal(TIMESTAMP)
    @Column(name = "published_at")
    protected LocalDateTime publishedAt;

    @Nullable
    @Column(name = "tx_hash")
    private String txHash;

    @Nullable
    @Column(name = "ledger_dispatch_status_error_reason")
    private String ledgerDispatchStatusErrorReason;

    @Nullable
    @Column(name = "funding_hash")
    private String fundingHash;

    /** Identifying name of the entity providing the funding. Populated for FUNDING events only. */
    @Nullable
    @Column(name = "funding_entity")
    private String fundingEntity;

    @NotNull
    @Column(name = "total_amount", nullable = false)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @NotBlank
    @Column(name = "currency", nullable = false)
    private String currency;

    /** Milestone allocations for this event — one per (event, milestone) pair. */
    @Builder.Default
    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EventMilestoneAllocationEntity> milestoneAllocations = new ArrayList<>();

    /** Spend line items — populated only for SPENDING events. */
    @Builder.Default
    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SpendingItemEntity> spendingItems = new ArrayList<>();

    @Override
    public boolean isNew() {
        return isNew;
    }

}
