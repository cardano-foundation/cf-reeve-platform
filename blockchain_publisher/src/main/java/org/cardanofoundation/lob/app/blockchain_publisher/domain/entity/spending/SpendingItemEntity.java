package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
@Entity(name = "blockchain_publisher.spending.spending_item_entity")
@Table(name = "blockchain_publisher_spending_item")
@Builder
@Audited
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners({ AuditingEntityListener.class })
@Access(AccessType.FIELD)
public class SpendingItemEntity {

    @Id
    @Column(name = "item_id", nullable = false)
    private String itemId;

    @Column(name = "category", nullable = false)
    private String category;

    @ManyToOne
    @JoinColumn(name = "event_id")
    private SpendingEventEntity event;

    @NotBlank
    @Column(name = "vendor", nullable = false)
    private String vendor;

    @Column(name = "amount_fcy", nullable = false)
    private BigDecimal amountFcy;

    @Column(name = "amount_rcy", nullable = false)
    private BigDecimal amountRcy;

    @NotBlank
    @Column(name = "currency", nullable = false)
    private String currency;

    @Nullable
    @Column(name = "currency_id")
    private String currencyId;

    @Column(name = "fx_rate", nullable = false)
    private BigDecimal fxRate;

    @Column(name = "spend_date", nullable = false)
    private LocalDate spendDate;

    @Nullable
    @Column(name = "document_hash")
    private String documentHash;

    @Nullable
    @Column(name = "notes", nullable = true)
    private String notes;
}
