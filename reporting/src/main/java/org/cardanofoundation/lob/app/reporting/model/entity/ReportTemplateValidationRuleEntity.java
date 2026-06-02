package org.cardanofoundation.lob.app.reporting.model.entity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.cardanofoundation.lob.app.reporting.model.enums.ComparisonOperator;
import org.cardanofoundation.lob.app.reporting.model.enums.TermSide;
import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@Setter
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "report_template_validation_rule")
@Entity
public class ReportTemplateValidationRuleEntity extends CommonEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @OneToMany(mappedBy = "validationRule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ValidationRuleTermEntity> terms = new ArrayList<>();

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ComparisonOperator operator;

    private String name;

    @Builder.Default
    private boolean active = true;

    @JoinColumn(name = "report_template_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY)
    private ReportTemplateEntity reportTemplate;

    public int computeContentHash() {
        return Objects.hash(
            operator.name(),
            active,
            terms.stream().filter(validationRuleTermEntity -> validationRuleTermEntity.getSide().equals(TermSide.LEFT)).map(ValidationRuleTermEntity::computeContentHash).toList(),
            terms.stream().filter(validationRuleTermEntity -> validationRuleTermEntity.getSide().equals(TermSide.RIGHT)).map(ValidationRuleTermEntity::computeContentHash).toList()
        );
    }

}
