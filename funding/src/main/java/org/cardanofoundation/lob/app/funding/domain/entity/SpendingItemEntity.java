package org.cardanofoundation.lob.app.funding.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
@Entity(name = "funding.SpendingItemEntity")
@Table(name = "funding_spending_item")
@Audited
@EntityListeners({AuditingEntityListener.class})
public class SpendingItemEntity extends CommonEntity implements Persistable<String> {

    @Id
    @Column(name = "item_id", nullable = false)
    private String id;

    @NotBlank
    @Column(name = "category", nullable = false)
    private String category;

    @NotBlank
    @Column(name = "vendor", nullable = false)
    private String vendor;

    @NotNull
    @Column(name = "amount_fcy", nullable = false)
    private BigDecimal amountFcy;

    @NotBlank
    @Column(name = "currency", nullable = false)
    private String currency;

    @Nullable
    @Column(name = "fx_rate")
    private BigDecimal fxRate;

    @Nullable
    @Column(name = "amount_rcy")
    private BigDecimal amountRcy;

    @NotNull
    @Column(name = "spend_date", nullable = false)
    private LocalDate spendDate;

    @Nullable
    @Column(name = "hash")
    private String hash;

    @Nullable
    @Column(name = "notes")
    private String notes;

    @Column(name = "event_id", insertable = false, updatable = false)
    private String eventId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private SpendingEventEntity event;

    @Override
    public boolean isNew() {
        return isNew;
    }

}
