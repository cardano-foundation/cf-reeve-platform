package org.cardanofoundation.lob.app.funding.domain.entity;

import java.util.ArrayList;
import java.util.List;

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
@Entity(name = "funding.EventProjectAllocationEntity")
@Table(name = "funding_event_project_allocation")
@Audited
@EntityListeners({AuditingEntityListener.class})
public class EventProjectAllocationEntity extends CommonEntity implements Persistable<EventProjectAllocationEntity.Id> {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "eventId",   column = @Column(name = "event_id")),
            @AttributeOverride(name = "projectId", column = @Column(name = "project_id"))
    })
    private Id id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", insertable = false, updatable = false)
    private FundingEventEntity event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", insertable = false, updatable = false)
    private ProjectEntity project;

    /** Milestone allocations for this project within the event. */
    @Builder.Default
    @OneToMany(mappedBy = "allocation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EventMilestoneAllocationEntity> milestoneAllocations = new ArrayList<>();

    @Embeddable
    @AllArgsConstructor
    @NoArgsConstructor
    @EqualsAndHashCode
    @Getter
    @Setter
    public static class Id {
        @Column(name = "event_id")
        private String eventId;

        @Column(name = "project_id")
        private String projectId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

}
