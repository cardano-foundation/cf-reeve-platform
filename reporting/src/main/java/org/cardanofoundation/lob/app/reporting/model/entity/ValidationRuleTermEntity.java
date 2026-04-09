package org.cardanofoundation.lob.app.reporting.model.entity;

import java.util.Objects;

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
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import org.cardanofoundation.lob.app.reporting.model.enums.TermOperation;
import org.cardanofoundation.lob.app.reporting.model.enums.TermSide;
import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@Entity
@Table(name = "report_template_validation_rule_term")
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
public class ValidationRuleTermEntity extends CommonEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "validation_rule_id", nullable = false)
    private ReportTemplateValidationRuleEntity validationRule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "field_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) // Add this
    private ReportTemplateFieldEntity field;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TermOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TermSide side;

    @Column(name = "term_order", nullable = false)
    private int termOrder;

    public int computeContentHash() {
        StringBuilder name = new StringBuilder();
        ReportTemplateFieldEntity entity = this.getField();
        while(entity != null) {
            String partName = (entity.getParentField() != null ?  "." + entity.getName() : entity.getName());
            name.insert(0, partName);
            entity = entity.getParentField();
        }
        return Objects.hash(
                name.toString(),
            operation.name()
        );
    }
}
