package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports;

import java.util.Map;
import java.util.Optional;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.support.spring_audit.CommonDateOnlyEntity;

@Entity(name = "blockchain_publisher.report.ReportEntityV2")
@Table(name = "blockchain_publisher_report_v2")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
@EntityListeners({AuditingEntityListener.class})
public class ReportEntity extends CommonDateOnlyEntity implements Persistable<String> {

    @Id
    private String id;
    private String organisationId;
    @Enumerated(EnumType.STRING)
    private ReportTemplateType reportTemplateType;
    private Long reportTemplateVer;
    private Long reportVer;
    @Enumerated(EnumType.STRING)
    private IntervalType intervalType;
    private short period;
    private short year;
    @Enumerated(EnumType.STRING)
    private DataMode dataMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_data", columnDefinition = "json")
    private Map<String, Object> reportData;

    @Nullable
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "transactionHash", column = @Column(name = "l1_transaction_hash", length = 64)),
            @AttributeOverride(name = "absoluteSlot", column = @Column(name = "l1_absolute_slot")),
            @AttributeOverride(name = "creationSlot", column = @Column(name = "l1_creation_slot")),
            @AttributeOverride(name = "finalityScore", column = @Column(name = "l1_finality_score", columnDefinition = "blockchain_publisher_finality_score_type")),
            @AttributeOverride(name = "publishStatus", column = @Column(name = "l1_publish_status", columnDefinition = "blockchain_publisher_blockchain_publish_status_type")),
            @AttributeOverride(name = "publishStatusErrorReason", column = @Column(name = "l1_publish_status_error_reason")),
            @AttributeOverride(name = "publishRetry", column = @Column(name = "l1_publish_retry"))
    })
    private L1SubmissionData l1SubmissionData;

    public Optional<L1SubmissionData> getL1SubmissionData() {
        return Optional.ofNullable(l1SubmissionData);
    }

    public void setL1SubmissionData(Optional<L1SubmissionData> l1SubmissionData) {
        this.l1SubmissionData = l1SubmissionData.orElse(null);
    }
}
