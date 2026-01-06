package org.cardanofoundation.lob.app.reporting.model.entity;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountSubType;
import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@Entity
@Table(name = "report_template_field")
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
public class ReportTemplateFieldEntity extends CommonEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_template_id", nullable = false)
    private ReportTemplateEntity reportTemplate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_field_id")
    private ReportTemplateFieldEntity parentField;

    @OneToMany(mappedBy = "parentField", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ReportTemplateFieldEntity> childFields = new ArrayList<>();

    @OneToMany(mappedBy = "field", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ValidationRuleTermEntity> validationRuleTerms = new ArrayList<>();

    @ManyToMany
    @JoinTable(
            name = "report_field_subtype_mapping",
            joinColumns = @JoinColumn(name = "field_id"),
            inverseJoinColumns = @JoinColumn(name = "sub_type_id")
    )
    @Builder.Default
    private List<ChartOfAccountSubType> mappingTypes = new ArrayList<>();

    private String name;
    // Additional properties for column behavior
    @Builder.Default
    private boolean accumulated = false;
    @Builder.Default
    private boolean accumulatedYearly = false;
    @Builder.Default
    private boolean accumulatedPreviousYear = false;
    @Builder.Default
    private boolean negated = false;

}
