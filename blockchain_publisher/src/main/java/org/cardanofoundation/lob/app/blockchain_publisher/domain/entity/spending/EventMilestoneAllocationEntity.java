package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending;

import java.math.BigDecimal;
import java.time.LocalDate;

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

    @Column(name = "milestone_id", nullable = false)
    private String milestoneId;

    @ManyToOne
    @JoinColumn(name = "event_id")
    private SpendingEventEntity event;

    @NotBlank
    @Column(name = "milestone_label", nullable = false)
    private String milestoneLabel;

    @Nullable
    @Column(name = "expected_cost")
    private BigDecimal expectedCost;

    @Nullable
    @Column(name = "allocated_amount")
    private BigDecimal allocatedAmount;

    @NotBlank
    @Column(name = "currency", nullable = false)
    private String currency;

    @Nullable
    @Column(name = "currency_id")
    private String currencyId;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;
}
