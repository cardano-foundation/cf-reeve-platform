package org.cardanofoundation.lob.app.funding.domain.entity;

import java.math.BigDecimal;
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

import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@Entity(name = "funding.SpendingEventEntity")
@Table(name = "funding_spending_event")
@Audited
@EntityListeners({AuditingEntityListener.class})
public class SpendingEventEntity extends CommonEntity implements Persistable<String> {

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
    @Column(name = "funding_id", nullable = false)
    private String fundingId;

    @NotBlank
    @Column(name = "activity_id", nullable = false)
    private String activityId;

    @Nullable
    @Column(name = "tx_hash")
    private String txHash;

    @Nullable
    @Column(name = "funding_tx")
    private String fundingTx;

    @NotNull
    @Column(name = "total_amount", nullable = false)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @NotBlank
    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "project_id", insertable = false, updatable = false)
    private String projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    /** Used only for SPENDING events — stores the FK for direct access without lazy loading. */
    @Nullable
    @Column(name = "milestone_id")
    private String milestoneId;

    /** Used only for SPENDING events — the milestone this batch of spends targets. */
    @Nullable
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "milestone_id", insertable = false, updatable = false)
    private MilestoneEntity milestone;

    /** Spend line items — populated only for SPENDING events. */
    @Builder.Default
    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL)
    private List<SpendingItemEntity> spendingItems = new ArrayList<>();

    /** Milestone allocations — populated only for FUNDING and REFUND events. */
    @Builder.Default
    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL)
    private List<EventMilestoneAllocationEntity> milestoneAllocations = new ArrayList<>();

    @Override
    public boolean isNew() {
        return isNew;
    }

}
