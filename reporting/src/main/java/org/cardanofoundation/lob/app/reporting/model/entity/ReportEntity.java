package org.cardanofoundation.lob.app.reporting.model.entity;

import static jakarta.persistence.EnumType.STRING;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.model.enums.PublishError;
import org.cardanofoundation.lob.app.support.crypto.SHA3;
import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@Entity
@Table(name = "report")
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
public class ReportEntity extends CommonEntity {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_template_id", nullable = false)
    private ReportTemplateEntity reportTemplate;

    @Builder.Default
    private long ver = 1;

    private String organisationId;
    private String name;
    @Enumerated(STRING)
    private IntervalType intervalType;
    private short period;
    private short year;

    @Enumerated(STRING)
    private DataMode dataMode;

    @Builder.Default
    private boolean isReadyToPublish = false;
    @Enumerated(STRING)
    private PublishError publishError;

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ReportFieldEntity> fields = new ArrayList<>();

    @Builder.Default
    private boolean ledgerDispatchApproved = false;
    @Builder.Default
    @Enumerated(STRING)
    private LedgerDispatchStatus ledgerDispatchStatus = LedgerDispatchStatus.NOT_DISPATCHED;
    private String ledgerDispatchStatusErrorReason;
    private LocalDateTime ledgerDispatchDate;
    private String publishedBy;

    @PrePersist
    private void generateId() {
        if (this.id == null) {
            String hashInput = String.format("%s:%s:%s:%s:%s:%s",
                organisationId,
                reportTemplate != null ? reportTemplate.getId() : "",
                intervalType,
                period,
                year,
                ver
            );
            this.id = SHA3.digestAsHex(hashInput);
        }
    }
}
