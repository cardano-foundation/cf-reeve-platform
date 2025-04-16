package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import javax.annotation.Nullable;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.google.common.base.Objects;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;
import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionBatchStatus;
import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@Getter
@Setter
@Entity(name = "accounting_reporting_core.TransactionBatchEntity")
@Table(name = "accounting_core_transaction_batch")
@NoArgsConstructor
@Audited
@EntityListeners({ AuditingEntityListener.class })
public class TransactionBatchEntity extends CommonEntity implements Persistable<String> {

    @Id
    @Column(name = "transaction_batch_id", nullable = false)
    @NotNull
    private String id;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "organisationId", column = @Column(name = "filtering_parameters_organisation_id")),
            @AttributeOverride(name = "from", column = @Column(name = "filtering_parameters_from_date")),
            @AttributeOverride(name = "to", column = @Column(name = "filtering_parameters_to_date")),
            @AttributeOverride(name = "transactionTypes", column = @Column(name = "filtering_parameters_transaction_types")),
            @AttributeOverride(name = "transactionNumbers", column = @Column(name = "filtering_parameters_transaction_numbers", columnDefinition = "text[]", nullable = false)),
            @AttributeOverride(name = "accountingPeriodFrom", column = @Column(name = "filtering_parameters_accounting_period_from")),
            @AttributeOverride(name = "accountingPeriodTo", column = @Column(name = "filtering_parameters_accounting_period_to")),
    })
    @NotNull
    private FilteringParameters filteringParameters;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "accounting_core_transaction_batch_assoc",
            joinColumns = @JoinColumn(name = "transaction_batch_id"),
            inverseJoinColumns = @JoinColumn(name = "transaction_id"))
    @NotNull
    private Set<TransactionEntity> transactions = new LinkedHashSet<>();

    @AttributeOverrides({
            @AttributeOverride(name = "totalTransactionsCount", column = @Column(name = "stats_total_transactions_count")),
            @AttributeOverride(name = "processedTransactionsCount", column = @Column(name = "stats_processed_transactions_count")),
            @AttributeOverride(name = "failedTransactionsCount", column = @Column(name = "stats_failed_transactions_count")),
            @AttributeOverride(name = "approvedTransactionsCount", column = @Column(name = "stats_approved_transactions_count")),
            @AttributeOverride(name = "approvedTransactionsDispatchCount", column = @Column(name = "stats_approved_transactions_dispatch_count")),
            @AttributeOverride(name = "dispatchedTransactionsCount", column = @Column(name = "stats_dispatched_transactions_count")),
            @AttributeOverride(name = "completedTransactionsCount", column = @Column(name = "stats_completed_transactions_count")),
            @AttributeOverride(name = "finalizedTransactionsCount", column = @Column(name = "stats_finalized_transactions_count")),
            @AttributeOverride(name = "failedSourceERPTransactionsCount", column = @Column(name = "stats_failed_source_erp_transactions_count")),
            @AttributeOverride(name = "failedSourceLOBTransactionsCount", column = @Column(name = "stats_failed_source_lob_transactions_count")),
    })
    @Nullable
    private BatchStatistics batchStatistics = new BatchStatistics();

    @Nullable
    @AttributeOverrides({
            @AttributeOverride(name = "code", column = @Column(name = "detail_code")),
            @AttributeOverride(name = "subCode", column = @Column(name = "detail_subcode")),
            @AttributeOverride(name = "bag", column = @Column(name = "detail_bag"))
    })
    private Details details;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @JdbcType(PostgreSQLEnumJdbcType.class)
    private TransactionBatchStatus status = TransactionBatchStatus.CREATED;

    public String getOrganisationId() {
        return filteringParameters.getOrganisationId();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransactionBatchEntity that = (TransactionBatchEntity) o;

        return Objects.equal(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return STR."TransactionBatchEntity{id='\{id}\{'\''}, createdBy='\{createdBy}\{'\''}, updatedBy='\{updatedBy}\{'\''}, createdAt=\{createdAt}, updatedAt=\{updatedAt}\{'}'}";
    }

    public Optional<BatchStatistics> getBatchStatistics() {
        return Optional.ofNullable(batchStatistics);
    }

    @Override
    public String getId() {
        return id;
    }

    public Optional<Details> getDetails() {
        return Optional.ofNullable(details);
    }

}
