package org.cardanofoundation.lob.app.reporting.model.entity;

import static jakarta.persistence.EnumType.STRING;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccount;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportFieldDateRange;
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
    @OrderBy("fieldOrder ASC")
    @Builder.Default
    private List<ReportTemplateFieldEntity> childFields = new ArrayList<>();

    @OneToMany(mappedBy = "field", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ValidationRuleTermEntity> validationRuleTerms = new ArrayList<>();

    @ManyToMany
    @JoinTable(
            name = "report_field_account_mapping",
            joinColumns = @JoinColumn(name = "field_id"),
            inverseJoinColumns = {
                    @JoinColumn(name = "organisation_id", referencedColumnName = "organisation_id"),
                    @JoinColumn(name = "customer_code", referencedColumnName = "customer_code")
            }
    )
    @Builder.Default
    private Set<ChartOfAccount> mappingAccounts = new HashSet<>();

    private String name;

    @Builder.Default
    private int fieldOrder = 0;

    // Additional properties for column behavior
    @Enumerated(STRING)
    @Builder.Default
    private ReportFieldDateRange dateRange = ReportFieldDateRange.PERIOD;

    @Builder.Default
    private boolean negated = false;

    /**
     * Computes a hash based on: childFields, name, dateRange, and negated.
     * Used for quick comparison with DTOs to detect changes.
     */
    public int computeContentHash() {
        return Objects.hash(
                hashChildFields(),
                name,
                childFields.isEmpty() ? dateRange : null, // if there are child fields, the dateRange is determined by them, so we only include it in the hash if there are no child fields
                negated
        );
    }

    /**
     * Helper method to compute hash of child fields recursively.
     */
    private int hashChildFields() {
        if (childFields == null || childFields.isEmpty()) {
            return 0;
        }
        int hash = 1;
        for (ReportTemplateFieldEntity child : childFields) {
            hash = 31 * hash + child.computeContentHash();
        }
        return hash;
    }

}
