package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.annotations.LOBVersionSourceRelevant;

@Entity(name = "accounting_reporting_core.report.ReportDynamicFieldEntity")
@Table(name = "accounting_core_report")
@NoArgsConstructor
@AllArgsConstructor
@Audited
@EntityListeners({AuditingEntityListener.class})
public class ReportDynamicFieldEntity {
    @Id
    @Column(name = "id", nullable = false)
    @LOBVersionSourceRelevant
    @Setter
    private String id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "report_id")
    @Getter
    @Setter
    private ReportDynamicEntity reportDynamic;

    @JoinColumn(name = "field_id")
    @Getter
    @Setter
    private String fieldId;

    @JoinColumn(name = "amount")
    @Getter
    @Setter
    private String amount;
}
