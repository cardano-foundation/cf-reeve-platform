package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity;

import java.math.BigDecimal;
import java.util.Optional;

import jakarta.persistence.*;

import javax.annotation.Nullable;

import lombok.*;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.google.common.base.Objects;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JoinColumnOrFormula;
import org.hibernate.annotations.JoinFormula;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.annotations.LOBVersionSourceRelevant;
import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@Entity(name = "accounting_reporting_core.TransactionItemEntity")
@Table(name = "accounting_core_transaction_item")
@NoArgsConstructor
@ToString
@AllArgsConstructor
@Audited
@EntityListeners({ AuditingEntityListener.class })
public class TransactionItemEntity extends CommonEntity implements Persistable<String> {

    @Id
    @Column(name = "transaction_item_id", nullable = false)
    @LOBVersionSourceRelevant

    @Setter
    private String id;

    @Override
    public String getId() {
        return id;
    }

    @AttributeOverrides({
            @AttributeOverride(name = "code", column = @Column(name = "account_code_debit")),
            @AttributeOverride(name = "refCode", column = @Column(name = "account_ref_code_debit")),
            @AttributeOverride(name = "name", column = @Column(name = "account_name_debit"))
    })
    @Nullable
    private Account accountDebit;

    @AttributeOverrides({
            @AttributeOverride(name = "code", column = @Column(name = "account_code_credit")),
            @AttributeOverride(name = "refCode", column = @Column(name = "account_ref_code_credit")),
            @AttributeOverride(name = "name", column = @Column(name = "account_name_credit"))
    })
    @Nullable
    private Account accountCredit;

    @Nullable
    @AttributeOverrides({
            @AttributeOverride(name = "code", column = @Column(name = "account_event_code")),
            @AttributeOverride(name = "name", column = @Column(name = "account_event_name")),
    })
    private AccountEvent accountEvent;

    @Column(name = "amount_fcy", nullable = false)
    @LOBVersionSourceRelevant
    @Getter
    @Setter
    private BigDecimal amountFcy;

    @Column(name = "amount_lcy", nullable = false)
    @LOBVersionSourceRelevant
    @Getter
    @Setter
    private BigDecimal amountLcy;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "rejectionReason", column = @Column(name = "rejection_reason", columnDefinition = "accounting_core_rejection_reason_type")),
    })
    @Nullable
    private Rejection rejection;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "transaction_id")
    @Getter
    @Setter
    private TransactionEntity transaction;

    @Column(name = "fx_rate", nullable = false)
    @LOBVersionSourceRelevant
    @Getter
    @Setter
    private BigDecimal fxRate;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "customerCode", column = @Column(name = "project_customer_code")),
            @AttributeOverride(name = "externalCustomerCode", column = @Column(name = "project_external_customer_code")),
            @AttributeOverride(name = "name", column = @Column(name = "project_name"))
    })
    @Nullable
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumnOrFormula(column = @JoinColumn(name = "project_customer_code", referencedColumnName = "customer_code", insertable = false, updatable = false))
    @JoinColumnOrFormula(formula = @JoinFormula(value = "(SELECT t.organisation_id FROM accounting_core_transaction t WHERE t.transaction_id = transaction_id)", referencedColumnName = "organisation_id"))
    @NotAudited
    @Nullable
    @Setter
    private org.cardanofoundation.lob.app.organisation.domain.entity.Project mappedProject;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "customerCode", column = @Column(name = "cost_center_customer_code")),
            @AttributeOverride(name = "externalCustomerCode", column = @Column(name = "cost_center_external_customer_code")),
            @AttributeOverride(name = "name", column = @Column(name = "cost_center_name"))
    })
    @Nullable
    private CostCenter costCenter;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumnOrFormula(column = @JoinColumn(name = "cost_center_customer_code", referencedColumnName = "customer_code", insertable = false, updatable = false))
    @JoinColumnOrFormula(formula = @JoinFormula(value = "(SELECT t.organisation_id FROM accounting_core_transaction t WHERE t.transaction_id = transaction_id)", referencedColumnName = "organisation_id"))
    @NotAudited
    @Nullable
    @Setter
    private org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter mappedCostCenter;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "num", column = @Column(name = "document_num")),

            @AttributeOverride(name = "currency.id", column = @Column(name = "document_currency_id")),
            @AttributeOverride(name = "currency.customerCode", column = @Column(name = "document_currency_customer_code")),

            @AttributeOverride(name = "vat.customerCode", column = @Column(name = "document_vat_customer_code")),
            @AttributeOverride(name = "vat.rate", column = @Column(name = "document_vat_rate")),

            @AttributeOverride(name = "counterparty.customerCode", column = @Column(name = "document_counterparty_customer_code")),
            @AttributeOverride(name = "counterparty.type", column = @Column(name = "document_counterparty_type")),
            @AttributeOverride(name = "counterparty.name", column = @Column(name = "document_counterparty_name")),
    })

    private Document document;

    @Column(name = "status", nullable = false)
    @Getter
    @Setter
    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    private TxItemValidationStatus status = TxItemValidationStatus.OK;

    @Getter
    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    private OperationType operationType;

    public void clearAccountCodeCredit() {
        this.accountCredit = null;
    }

    public void clearAccountCodeDebit() {
        this.accountDebit = null;
    }

    public Optional<Account> getAccountDebit() {
        return Optional.ofNullable(accountDebit);
    }

    public Optional<Account> getAccountCredit() {
        return Optional.ofNullable(accountCredit);
    }

    public Optional<AccountEvent> getAccountEvent() {
        return Optional.ofNullable(accountEvent);
    }

    public Optional<Project> getProject() {
        return Optional.ofNullable(project);
    }

    public Optional<CostCenter> getCostCenter() {
        return Optional.ofNullable(costCenter);
    }

    public Optional<Document> getDocument() {
        return Optional.ofNullable(document);
    }

    public Optional<Rejection> getRejection() {
        return Optional.ofNullable(rejection);
    }

    // setters
    public void setAccountDebit(Optional<Account> account) {
        this.accountDebit = account.orElse(null);
    }

    public void setAccountCredit(Optional<Account> account) {
        this.accountCredit = account.orElse(null);
    }

    public void setAccountEvent(Optional<AccountEvent> accountEvent) {
        this.accountEvent = accountEvent.orElse(null);
    }

    public void setProject(Optional<Project> project) {
        this.project = project.orElse(null);
    }

    public void setCostCenter(Optional<CostCenter> costCenter) {
        this.costCenter = costCenter.orElse(null);
    }

    public void setDocument(Optional<Document> document) {
        this.document = document.orElse(null);
    }

    public void setRejection(Optional<Rejection> rejection) {
        this.rejection = rejection.orElse(null);
    }

    // Helper methods for mapped entities
    public Optional<org.cardanofoundation.lob.app.organisation.domain.entity.Project> getMappedProject() {
        return Optional.ofNullable(mappedProject);
    }

    public Optional<org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter> getMappedCostCenter() {
        return Optional.ofNullable(mappedCostCenter);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        val that = (TransactionItemEntity) o;

        return Objects.equal(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

}
