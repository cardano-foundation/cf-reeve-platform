package org.cardanofoundation.lob.app.funding.domain.entity;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.TemporalType.TIMESTAMP;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.cardanofoundation.lob.app.support.crypto.SHA3;
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

    @Nullable
    @Column(name = "funding_hash")
    private String fundingHash;

    /** Identifying name of the entity providing the funding. Populated for FUNDING events only. */
    @Nullable
    @Column(name = "funding_entity")
    private String fundingEntity;

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

    @NotNull
    @Column(name = "total_amount", nullable = false)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @NotBlank
    @Column(name = "currency_rcy", nullable = false)
    private String currencyRcy;

    /** Event date. Applies to all event types; for a SPENDING event this is the spend date. */
    @Nullable
    @Column(name = "event_date")
    private LocalDate eventDate;

    // --- Spend detail: the event's single spend record. Populated for SPENDING events only. ---

    @Nullable
    @Column(name = "category")
    private String category;

    @Nullable
    @Column(name = "vendor")
    private String vendor;

    /** Foreign-currency amount spent ({@code amountFcy = amountRcy * fxRate}). */
    @Nullable
    @Column(name = "amount_fcy")
    private BigDecimal amountFcy;

    /** Reporting-currency amount spent. */
    @Nullable
    @Column(name = "amount_rcy")
    private BigDecimal amountRcy;

    /** Foreign currency of the spend (e.g. EUR); {@link #currencyRcy} is the reporting currency. */
    @Nullable
    @Column(name = "currency_fcy")
    private String currencyFcy;

    @Nullable
    @Column(name = "fx_rate")
    private BigDecimal fxRate;

    @Nullable
    @Column(name = "spend_hash")
    private String hash;

    @Nullable
    @Column(name = "notes")
    private String notes;

    /** Milestone allocations for this event — one per (event, milestone) pair, carrying only the allocated amount. */
    @Builder.Default
    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EventMilestoneAllocationEntity> milestoneAllocations = new ArrayList<>();

    @Override
    public boolean isNew() {
        return isNew;
    }

    /**
     * Deterministic, reproducible id for a funding event, derived from its immutable header
     * fields. Two events created for the same organisation, type, funding reference and currency
     * resolve to the same id (idempotent creation). {@code fundingHash} is part of the key so that
     * distinct fundings sharing a funding id (but a different hash) do not collide.
     *
     * <p>Computed in the service before child allocations/items are built — it intentionally does
     * <em>not</em> depend on children (a spending item's id is derived from the event id, so
     * including item ids here would be circular) nor on {@code totalAmount} (derived later).
     */
    public static String id(String organisationId,
                            EventType eventType,
                            String fundingId,
                            String fundingHash,
                            String currencyRcy) {
        return SHA3.digestAsHex("%s::%s::%s::%s::%s".formatted(
                organisationId,
                eventType,
                fundingId,
                fundingHash == null ? "" : fundingHash,
                currencyRcy));
    }

}
