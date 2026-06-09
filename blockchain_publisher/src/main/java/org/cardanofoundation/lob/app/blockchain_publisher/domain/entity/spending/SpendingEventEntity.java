package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionItemEntity;
import org.cardanofoundation.lob.app.support.spring_audit.CommonDateOnlyLockableEntity;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;
import org.hibernate.envers.Audited;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.annotation.Nullable;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.EAGER;

@Getter
@Setter
@Entity(name = "blockchain_publisher.spending.SpendingEventEntity")
@Table(name = "blockchain_publisher_spending_event")
@Builder
@Audited
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners({ AuditingEntityListener.class })
@Access(AccessType.FIELD)
public class SpendingEventEntity extends CommonDateOnlyLockableEntity implements Persistable<String> {

    @Id
    @Column(name = "transaction_id", nullable = false)
    private String id;

    @Column(name = "internal_number", nullable = false)
    private String internalNumber;

    @Column(name = "batch_id", nullable = false)
    private String batchId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "organisation_id")),
            @AttributeOverride(name = "name", column = @Column(name = "organisation_name")),
            @AttributeOverride(name = "taxIdNumber", column = @Column(name = "organisation_tax_id_number")),
            @AttributeOverride(name = "countryCode", column = @Column(name = "organisation_country_code")),
            @AttributeOverride(name = "currencyId", column = @Column(name = "organisation_currency_id")),
    })
    private Organisation organisation;

    @Column(name = "type", nullable = false)
    @Enumerated(STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    private TransactionType transactionType;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "accounting_period", nullable = false)
    private YearMonth accountingPeriod;

    @Nullable
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "transactionHash", column = @Column(name = "l1_transaction_hash")),
            @AttributeOverride(name = "absoluteSlot", column = @Column(name = "l1_absolute_slot")),
            @AttributeOverride(name = "creationSlot", column = @Column(name = "l1_creation_slot")),
            @AttributeOverride(name = "finalityScore", column = @Column(name = "l1_finality_score")),
            @AttributeOverride(name = "publishStatus", column = @Column(name = "l1_publish_status")),
            @AttributeOverride(name = "publishStatusErrorReason", column = @Column(name = "l1_publish_status_error_reason")),
            @AttributeOverride(name = "publishRetry", column = @Column(name = "l1_publish_retry"))
    })
    private L1SubmissionData l1SubmissionData;

    @OneToMany(mappedBy = "transaction", orphanRemoval = true, fetch = EAGER, cascade = CascadeType.ALL)
    @Builder.Default
    private Set<TransactionItemEntity> items = new LinkedHashSet<>();

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (obj instanceof SpendingEventEntity te) {
            return id.equals(te.getId());
        }

        return false;
    }

    public Optional<L1SubmissionData> getL1SubmissionData() {
        return Optional.ofNullable(l1SubmissionData);
    }

    public void setL1SubmissionData(Optional<L1SubmissionData> l1SubmissionData) {
        this.l1SubmissionData = l1SubmissionData.orElse(null);
    }

    @Override
    public String getId() {
        return id;
    }

}
