package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.EAGER;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus.NOT_DISPATCHED;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import javax.annotation.Nullable;

import lombok.*;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.google.common.base.Objects;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;
import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Validable;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.PublishError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportMode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.LedgerDispatchReceipt;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@Entity(name = "accounting_reporting_core.report.ReportDynamicEntity")
@Table(name = "accounting_core_report")
@NoArgsConstructor
@AllArgsConstructor
@Audited
@EntityListeners({AuditingEntityListener.class})
public class ReportDynamicEntity extends CommonEntity implements Persistable<String>, Validable {

    @Id
    @Column(name = "report_id", nullable = false, length = 64)
    @NotBlank
    @Getter
    @Setter
    private String reportId;

    @Column(name = "id_control", nullable = false, length = 64)
    @NotBlank
    @Getter
    @Setter
    private String idControl;

    @Column(name = "ver", nullable = false)
    @Getter
    @Setter
    private long ver = 1;

    @Override
    public String getId() {
        return reportId;
    }

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

    @Enumerated(STRING)
    @Column(name = "type", nullable = false)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @NotNull
    @Getter
    @Setter
    private ReportType type;

    @Enumerated(STRING)
    @Column(name = "interval_type", nullable = false)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @NotNull
    @Getter
    private IntervalType intervalType;

    @Column(name = "year", nullable = false)
    @Min(1900)
    @Max(4000)
    @Getter
    @Setter
    private Short year; // SMALLINT in PostgreSQL, mapped to Java's short

    @Column(name = "period")
    @Min(1)
    @Max(12)
    @Nullable
    private Short period; // SMALLINT in PostgreSQL, mapped to Java's short

    @Enumerated(STRING)
    @Column(name = "mode", nullable = false)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Getter
    @Setter
    private ReportMode mode; // USER or SYSTEM report

    @Column(name = "date", nullable = false)
    @NotNull
    @Getter
    @Setter
    private LocalDate date;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "primaryBlockchainType", column = @Column(name = "primary_blockchain_type")),
            @AttributeOverride(name = "primaryBlockchainHash", column = @Column(name = "primary_blockchain_hash"))
    })
    @Setter
    @Nullable
    private LedgerDispatchReceipt ledgerDispatchReceipt;

    @Column(name = "ledger_dispatch_approved", nullable = false)
    @Getter
    @Setter
    private Boolean ledgerDispatchApproved = false;

    @Column(name = "is_ready_to_publish", nullable = false)
    @Getter
    @Setter
    private Boolean isReadyToPublish = false;

    @Column(name = "publish_error")
    @Enumerated(STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Getter
    @Setter
    private PublishError publishError;

    @Column(name = "ledger_dispatch_status", nullable = false)
    @Enumerated(STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Getter
    @Setter
    // https://www.baeldung.com/java-enums-jpa-postgresql
    private LedgerDispatchStatus ledgerDispatchStatus = NOT_DISPATCHED;

    @Getter
    @Setter
    @Column(name = "ledger_dispatch_status_error_reason")
    private String ledgerDispatchStatusErrorReason;

    @Column(name = "ledger_dispatch_date")
    @Getter
    @Setter
    private LocalDateTime ledgerDispatchDate;

    @Column(name = "published_by")
    @Getter
    @Setter
    private String publishedBy;

    @Getter
    @Setter
    @OneToMany(mappedBy = "reportDynamic", orphanRemoval = true, fetch = EAGER,
            cascade = CascadeType.ALL)
    //@Builder.Default
    private Set<ReportDynamicFieldEntity> fields = new LinkedHashSet<>();

    public void setIntervalType(IntervalType intervalType){
        if (intervalType.equals(IntervalType.YEAR)) {
            this.period = null;
        }

        this.intervalType = intervalType;
    }

    public void addField(ReportDynamicFieldEntity field) {
        if (field != null) {
            this.fields.add(field);
            //field.setReportDynamic(this);
        }
    }

    @Override
    public boolean isValid() {
        return true;
    }

    public Optional<@Min(1) @Max(12) Short> getPeriod() {
        return Optional.ofNullable(period);
    }

    public void setPeriod(Optional<@Min(1) @Max(12) Short> period) {
        this.period = period.orElse(null);
    }

    public Optional<LedgerDispatchReceipt> getLedgerDispatchReceipt() {
        return Optional.ofNullable(ledgerDispatchReceipt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        val that = (ReportDynamicEntity) o;

        return Objects.equal(reportId, that.reportId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(reportId);
    }

}
