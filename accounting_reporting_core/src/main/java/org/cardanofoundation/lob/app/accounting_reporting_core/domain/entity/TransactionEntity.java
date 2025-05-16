package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.EAGER;
import static java.util.stream.Collectors.toSet;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus.NOT_DISPATCHED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.ERP;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import javax.annotation.Nullable;

import lombok.*;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.google.common.base.Objects;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;
import org.hibernate.envers.Audited;
import org.javers.core.metamodel.annotation.DiffIgnore;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.annotations.LOBVersionSourceRelevant;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.Reconcilation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationEntity;
import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@Entity(name = "accounting_reporting_core.TransactionEntity")
@Table(name = "accounting_core_transaction")
@NoArgsConstructor
@AllArgsConstructor
@Audited
@EntityListeners({ OverallStatusTransactionEntityListener.class, AuditingEntityListener.class })
public class TransactionEntity extends CommonEntity implements Persistable<String> {

    @Id
    @Column(name = "transaction_id", nullable = false)
    @LOBVersionSourceRelevant
    @Setter
    private String id;

    @Column(name = "transaction_internal_number", nullable = false)
    @LOBVersionSourceRelevant
    @Getter
    @Setter
    private String transactionInternalNumber;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "accounting_core_transaction_batch_assoc",
            joinColumns = @JoinColumn(name = "transaction_id"),
            inverseJoinColumns = @JoinColumn(name = "transaction_batch_id"))
    @NotNull
    @Getter
    private Set<TransactionBatchEntity> batches = new LinkedHashSet<>();

    @Column(name = "batch_id", nullable = false)
    @DiffIgnore
    @Getter
    @Setter
    private String batchId;

    @Column(name = "processing_status")
    @Enumerated(STRING)
    @Setter
    @DiffIgnore
    private TransactionProcessingStatus processingStatus;

    @Column(name = "accounting_period", nullable = false)
    @Getter
    @Setter
    private YearMonth accountingPeriod;

    @Column(name = "type", nullable = false)
    @Enumerated(STRING)
    @LOBVersionSourceRelevant
    @Getter
    @Setter
    @JdbcType(PostgreSQLEnumJdbcType.class)
    private TransactionType transactionType;

    @Column(name = "entry_date", nullable = false)
    @LOBVersionSourceRelevant
    @Getter
    @Setter
    private LocalDate entryDate;

    @Column(name = "ledger_dispatch_status", nullable = false)
    @Enumerated(STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Getter
    @Setter
    // https://www.baeldung.com/java-enums-jpa-postgresql
    private LedgerDispatchStatus ledgerDispatchStatus = NOT_DISPATCHED;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "primaryBlockchainType", column = @Column(name = "primary_blockchain_type")),
            @AttributeOverride(name = "primaryBlockchainHash", column = @Column(name = "primary_blockchain_hash"))
    })
    @Getter
    @Setter
    @Nullable
    private LedgerDispatchReceipt ledgerDispatchReceipt;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "organisation_id")),
            @AttributeOverride(name = "name", column = @Column(name = "organisation_name")),
            @AttributeOverride(name = "countryCode", column = @Column(name = "organisation_country_code")),
            @AttributeOverride(name = "taxIdNumber", column = @Column(name = "organisation_tax_id_number")),
            @AttributeOverride(name = "currencyId", column = @Column(name = "organisation_currency_id"))
    })
    @Getter
    @Setter
    private Organisation organisation;

    @Column(name = "automated_validation_status", nullable = false)
    @Enumerated(STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Getter
    @Setter
    private TxValidationStatus automatedValidationStatus = TxValidationStatus.VALIDATED;

    @Column(name = "transaction_approved", nullable = false)
    @Getter
    @Setter
    @DiffIgnore
    private Boolean transactionApproved = false;

    @Column(name = "ledger_dispatch_approved", nullable = false)
    @Getter
    @Setter
    @DiffIgnore
    private Boolean ledgerDispatchApproved = false;

    @OneToMany(mappedBy = "transaction", orphanRemoval = true, fetch = EAGER, cascade = CascadeType.ALL)
    private Set<TransactionItemEntity> items = new LinkedHashSet<>();

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reconcilation_id")
    @Nullable
    private ReconcilationEntity lastReconcilation;

    @Column(name = "user_comment")
    @Nullable
    private String userComment;

    @Column(name = "overall_status", nullable = false)
    @Enumerated(STRING)
    @DiffIgnore
    @Getter
    @Setter
    @JdbcType(PostgreSQLEnumJdbcType.class)
    private TransactionStatus overallStatus;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "source", column = @Column(name = "reconcilation_source")),
        @AttributeOverride(name = "sink", column = @Column(name = "reconcilation_sink")),
        @AttributeOverride(name = "finalStatus", column = @Column(name = "reconcilation_final_status"))
    })
    @Nullable
    @DiffIgnore
    private Reconcilation reconcilation;

    @ElementCollection(fetch = EAGER)
    @CollectionTable(name = "accounting_core_transaction_violation", joinColumns = @JoinColumn(name = "transaction_id"))
    @Audited
    @AttributeOverrides({
            @AttributeOverride(name = "code", column = @Column(name = "code", nullable = false)),
            @AttributeOverride(name = "subCode", column = @Column(name = "sub_code")),
            @AttributeOverride(name = "type", column = @Column(name = "type", nullable = false)),
            @AttributeOverride(name = "txItemId", column = @Column(name = "tx_item_id")),
            @AttributeOverride(name = "source", column = @Column(name = "source", nullable = false, columnDefinition = "accounting_core_source_type")),
            @AttributeOverride(name = "processorModule", column = @Column(name = "processor_module", nullable = false)),
            @AttributeOverride(name = "bag", column = @Column(name = "detail_bag", nullable = false))
    })
    @DiffIgnore
    @Getter
    @Setter
    private Set<TransactionViolation> violations = new LinkedHashSet<>();

    public Optional<TransactionProcessingStatus> getProcessingStatus() {
        return Optional.ofNullable(processingStatus);
    }

    @PrePersist
    @PreUpdate
    public void updateProcessingStatus() {
        recalcValidationStatus();
        if (getAutomatedValidationStatus() == FAILED) {
            if (getViolations().stream().anyMatch(v -> v.getSource() == ERP)) {
                this.setProcessingStatus(TransactionProcessingStatus.INVALID);
                return;
            }
            if (hasAnyRejection()) {
                if (getItems().stream().anyMatch(transactionItemEntity -> transactionItemEntity.getRejection().stream().anyMatch(rejection -> rejection.getRejectionReason().getSource() == ERP))) {
                    this.setProcessingStatus(TransactionProcessingStatus.INVALID);
                    return;
                }
                this.setProcessingStatus(TransactionProcessingStatus.PENDING);
                return;
            }
            this.setProcessingStatus(TransactionProcessingStatus.PENDING);
            return;
        }

        if (hasAnyRejection()) {
            if (getItems().stream().anyMatch(transactionItemEntity -> transactionItemEntity.getRejection().stream().anyMatch(rejection -> rejection.getRejectionReason().getSource() == ERP))) {
                this.setProcessingStatus(TransactionProcessingStatus.INVALID);
                return;
            }
            this.setProcessingStatus(TransactionProcessingStatus.PENDING);
            return;
        }

        switch (getLedgerDispatchStatus()) {
            case NOT_DISPATCHED, MARK_DISPATCH -> {
                if (Boolean.TRUE.equals(getLedgerDispatchApproved())) {
                    this.setProcessingStatus(TransactionProcessingStatus.PUBLISHED);
                    return;
                }

                if (Boolean.TRUE.equals(getTransactionApproved())) {
                    this.setProcessingStatus(TransactionProcessingStatus.PUBLISH);
                    return;
                }
            }

            case DISPATCHED, COMPLETED, FINALIZED -> {
                this.setProcessingStatus(TransactionProcessingStatus.PUBLISHED);
                return;
            }
        }
        this.setProcessingStatus(TransactionProcessingStatus.APPROVE);
    }

    public boolean allApprovalsPassedForTransactionDispatch() {
        return transactionApproved && ledgerDispatchApproved;
    }

    public void addViolation(TransactionViolation transactionViolation) {
        this.violations.add(transactionViolation);

        recalcValidationStatus();
    }

    public void clearAllViolations() {
        violations.clear();
        recalcValidationStatus();
    }

    public void clearAllViolations(Source source) {
        violations.removeIf(violation -> violation.getSource() == source);
        recalcValidationStatus();
    }

    public void clearAllItemsRejectionsSource(Source source) {
        for (TransactionItemEntity txItem : items) {
            if (txItem.getRejection().stream().anyMatch(rejection -> rejection.getRejectionReason().getSource() == source)) {
                txItem.setRejection(Optional.empty());
            }

        }
    }

    public boolean hasAnyRejection() {
        return items.stream().anyMatch(item -> item.getRejection().isPresent());
    }

    public boolean hasAnyRejection(Source source) {
        for (val txItem : items) {
            if (txItem.getRejection().stream().anyMatch(rejection -> rejection.getRejectionReason().getSource() == source)) {
                return true;
            }

        }
        return false;
    }

    /**
     * Use only in super exceptional and rare cases(!), typically only from unit tests(!)
     */
    public void setAllItems(Set<TransactionItemEntity> items) {
        this.items = items;
    }

    /**
     * Use only in super exceptional and rare cases(!), typically only from unit tests(!)
     * @return all items including ERASED tx items
     */
    public Set<TransactionItemEntity> getAllItems() {
        return items;
    }

    public void setItems(Set<TransactionItemEntity> items) {
        if (items.stream().anyMatch(item -> item.getStatus().isErased())) {
            throw new IllegalArgumentException("Cannot set items with ERASED status, please use setAllItems method");
        }

        this.items = items;
    }

    public Set<TransactionItemEntity> getItems() {
        return items.stream()
                .filter(item -> item.getStatus().isOK()).collect(toSet());
    }

    public boolean hasAnyItemsErased() {
        return items.stream().anyMatch(item -> item.getStatus().isErased());
    }

    public boolean hasAllItemsErased() {
        return items.stream().allMatch(item -> item.getStatus().isErased());
    }

    public boolean hasAnyViolation(Source source) {
        return violations.stream().anyMatch(violation -> violation.getSource().equals(source));
    }

    public boolean isRejectionFree() {
        return !hasAnyRejection();
    }

    private void recalcValidationStatus() {
        automatedValidationStatus = violations.isEmpty() ? TxValidationStatus.VALIDATED : FAILED;
    }

    public Optional<TransactionItemEntity> findItemById(String txItemId) {
        return items.stream().filter(item -> txItemId.equals(item.getId())).findFirst();
    }

    public boolean isDispatchable() {
        return overallStatus == TransactionStatus.OK
                && ledgerDispatchStatus == NOT_DISPATCHED;
    }

    public Optional<Reconcilation> getReconcilation() {
        return Optional.ofNullable(reconcilation);
    }

    public void setReconcilation(Optional<Reconcilation> reconcilation) {
        this.reconcilation = reconcilation.orElse(null);
    }

    public Optional<ReconcilationEntity> getLastReconcilation() {
        return Optional.ofNullable(lastReconcilation);
    }

    public void setLastReconcilation(Optional<ReconcilationEntity> parentReconcilation) {
        this.lastReconcilation = parentReconcilation.orElse(null);
    }

    public Optional<String> getUserComment() {
        return Optional.ofNullable(userComment);
    }

    public void setUserComment(Optional<String> userComment) {
        this.userComment = userComment.orElse(null);
    }

    public Optional<LedgerDispatchReceipt> getLedgerDispatchReceipt() {
        return Optional.ofNullable(ledgerDispatchReceipt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        val that = (TransactionEntity) o;

        return Objects.equal(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String toString() {
        return STR."TransactionEntity{id='\{id}\{'\''}, transactionInternalNumber='\{transactionInternalNumber}\{'\''}, batchId='\{batchId}'}";
    }
}
