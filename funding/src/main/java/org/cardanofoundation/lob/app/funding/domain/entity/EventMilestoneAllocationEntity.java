package org.cardanofoundation.lob.app.funding.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;

import lombok.*;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@Entity(name = "funding.EventMilestoneAllocationEntity")
@Table(name = "funding_event_milestone_allocation")
@Audited
@EntityListeners({AuditingEntityListener.class})
public class EventMilestoneAllocationEntity extends CommonEntity implements Persistable<EventMilestoneAllocationEntity.Id> {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "eventId",     column = @Column(name = "event_id")),
            @AttributeOverride(name = "milestoneId", column = @Column(name = "milestone_id"))
    })
    private Id id;

    @Nullable
    @Column(name = "allocated_amount")
    private BigDecimal allocatedAmount;

    // --- Spend detail: populated for SPENDING events only, null for FUNDING/REFUND ---

    @Nullable
    @Column(name = "category")
    private String category;

    @Nullable
    @Column(name = "vendor")
    private String vendor;

    /** Foreign-currency amount actually spent on this line. */
    @Nullable
    @Column(name = "amount_fcy")
    private BigDecimal amountFcy;

    /** Reporting-currency amount ({@code amountFcy = amountRcy * fxRate}). */
    @Nullable
    @Column(name = "amount_rcy")
    private BigDecimal amountRcy;

    /** Foreign currency of the spend (e.g. EUR); the event carries the reporting currency. */
    @Nullable
    @Column(name = "currency")
    private String currency;

    @Nullable
    @Column(name = "fx_rate")
    private BigDecimal fxRate;

    @Nullable
    @Column(name = "spend_date")
    private LocalDate spendDate;

    @Nullable
    @Column(name = "hash")
    private String hash;

    @Nullable
    @Column(name = "notes")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", insertable = false, updatable = false)
    private FundingEventEntity event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "milestone_id", insertable = false, updatable = false)
    private MilestoneEntity milestone;

    @Embeddable
    @AllArgsConstructor
    @NoArgsConstructor
    @EqualsAndHashCode
    @Getter
    @Setter
    public static class Id {
        @Column(name = "event_id")
        private String eventId;

        @Column(name = "milestone_id")
        private String milestoneId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

}
