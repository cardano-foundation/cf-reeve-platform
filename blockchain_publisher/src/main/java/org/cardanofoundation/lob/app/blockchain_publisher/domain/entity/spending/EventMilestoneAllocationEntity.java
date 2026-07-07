package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending;

import java.math.BigDecimal;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.envers.Audited;

/**
 * A single milestone within a {@link EventProjectAllocationEntity}. Carries only what the on-chain
 * {@code allocation[].milestones[]} record needs: the milestone identifier, its title and the
 * reporting-currency amount.
 */
@Getter
@Setter
@Entity(name = "blockchain_publisher.spending.event_milestone_allocation_entity")
@Table(name = "blockchain_publisher_event_milestone_allocation")
@Builder
@Audited
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners({ AuditingEntityListener.class })
@Access(AccessType.FIELD)
public class EventMilestoneAllocationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "project_allocation_id")
    private EventProjectAllocationEntity allocation;

    @Column(name = "milestone_id", nullable = false)
    private String milestoneId;

    @NotBlank
    @Column(name = "milestone_title", nullable = false)
    private String milestoneTitle;

    /** Amount allocated to this milestone by the event. */
    @Nullable
    @Column(name = "allocated_amount")
    private BigDecimal allocatedAmount;
}
