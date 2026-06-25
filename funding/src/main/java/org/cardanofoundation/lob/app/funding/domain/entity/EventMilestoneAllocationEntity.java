package org.cardanofoundation.lob.app.funding.domain.entity;

import java.math.BigDecimal;

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
            @AttributeOverride(name = "eventId",      column = @Column(name = "event_id")),
            @AttributeOverride(name = "projectUid",   column = @Column(name = "project_uid")),
            @AttributeOverride(name = "milestoneUid", column = @Column(name = "milestone_uid"))
    })
    private Id id;

    @Nullable
    @Column(name = "allocated_amount")
    private BigDecimal allocatedAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "event_id",   referencedColumnName = "event_id",   insertable = false, updatable = false),
            @JoinColumn(name = "project_uid", referencedColumnName = "project_uid", insertable = false, updatable = false)
    })
    private EventProjectAllocationEntity allocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "milestone_uid", insertable = false, updatable = false)
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

        @Column(name = "project_uid")
        private String projectUid;

        @Column(name = "milestone_uid")
        private String milestoneUid;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

}
