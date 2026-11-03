package org.cardanofoundation.lob.app.funding.domain.entity;

import java.math.BigDecimal;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;

import lombok.*;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

/**
 * An event's claim on a milestone's budget. {@code milestone_id} is no longer FK-enforced (LOB-2365
 * follow-up, migration {@code V1.8_200_9}): deleting a milestone/sub-project/project must not silently
 * take this row with it, so a row can outlive the milestone it names, standing as a deliberately
 * dangling reference — the owning event's {@code ERROR} status is what flags it for a human to review,
 * never automatic cleanup of this row itself. See {@link #milestone}.
 */
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", insertable = false, updatable = false)
    private FundingEventEntity event;

    /**
     * The milestone this allocation was made against, when it still exists — {@code milestone_id} is
     * no longer FK-enforced, so this row can outlive it. <strong>Do not call this Java-side getter</strong>
     * once a milestone might have been deleted: a lazy {@code @ManyToOne} whose target row is gone
     * throws {@code EntityNotFoundException} on access (we deliberately don't use
     * {@code @NotFound(IGNORE)} to suppress that — it forces this association to EAGER regardless of
     * {@code FetchType.LAZY}, which conflicts with {@code FundingEventEntity#milestoneAllocations}'s
     * {@code cascade = ALL} and throws a spurious {@code TransientObjectException} on flush, reproduced
     * end-to-end in {@code ProjectTreeUpdateE2ETest} — see LOB-2365 follow-up). This mapping exists only
     * so the JPQL queries below that path-navigate {@code a.milestone.project.id} keep working — those
     * compile to a SQL join and simply exclude a dangling row, no exception involved. A caller that needs
     * to know whether a specific allocation's milestone still exists should look it up explicitly via
     * {@code MilestoneRepository#findById(allocation.getId().getMilestoneId())} (an {@code Optional}, no
     * exception risk) instead of this getter.
     */
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
