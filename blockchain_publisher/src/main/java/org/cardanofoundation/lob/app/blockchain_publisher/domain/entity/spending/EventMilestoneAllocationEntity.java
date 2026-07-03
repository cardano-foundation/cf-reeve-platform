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

    // --- Spend detail: SPENDING events only ---

    @Nullable
    @Column(name = "category")
    private String category;

    @Nullable
    @Column(name = "vendor")
    private String vendor;

    @Nullable
    @Column(name = "amount_fcy")
    private BigDecimal amountFcy;

    /** Reporting-currency amount actually spent on this line ({@code amountFcy = amountRcy * fxRate}). */
    @Nullable
    @Column(name = "amount_rcy")
    private BigDecimal amountRcy;

    @Nullable
    @Column(name = "currency")
    private String currency;

    @Nullable
    @Column(name = "currency_id")
    private String currencyId;

    @Nullable
    @Column(name = "fx_rate")
    private BigDecimal fxRate;

    @Nullable
    @Column(name = "spend_date")
    private LocalDate spendDate;

    @Nullable
    @Column(name = "document_hash")
    private String documentHash;

    @Nullable
    @Column(name = "notes")
    private String notes;
}
