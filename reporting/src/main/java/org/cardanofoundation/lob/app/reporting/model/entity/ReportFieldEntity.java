package org.cardanofoundation.lob.app.reporting.model.entity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@Entity
@Table(name = "report_field")
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
public class ReportFieldEntity extends CommonEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private ReportEntity report;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "field_template_id")
    private ReportTemplateFieldEntity fieldTemplate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_field_id")
    private ReportFieldEntity parentField;

    @OneToMany(mappedBy = "parentField", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @Builder.Default
    private List<ReportFieldEntity> childFields = new ArrayList<>();

    private BigDecimal value;

    public BigDecimal getValue() {
        if(childFields.isEmpty()) return Optional.ofNullable(value).orElse(BigDecimal.ZERO);
        return childFields.stream()
                .map(ReportFieldEntity::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
