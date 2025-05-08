package org.cardanofoundation.lob.app.organisation.domain.entity;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "organisation_report_setup_field")
@Audited
@Builder
@EntityListeners({AuditingEntityListener.class})
public class ReportTypeFieldEntity extends CommonEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "name", nullable = false)
    private String name; // Liability, Equity, Revenue, Expense

    @OneToMany
    @JoinColumn(name = "parent_id")
    @Builder.Default
    private List<ReportTypeFieldEntity> childFields = new ArrayList<>();

    @ManyToOne
    @JoinColumn(name = "parent_id", insertable = false, updatable = false) // Reference to the parent
    private ReportTypeFieldEntity parent;

    @ManyToMany
    @JoinTable(
            name = "organisation_report_setup_field_subtype_mapping",
            joinColumns = @JoinColumn(name = "field_id"),
            inverseJoinColumns = @JoinColumn(name = "sub_type_id")
    )
    @Builder.Default
    private List<OrganisationChartOfAccountSubType> mappingTypes = new ArrayList<>();

    @ManyToMany
    @JoinTable(
            name = "organisation_report_setup_field_report_field_mapping",
            joinColumns = @JoinColumn(name = "field_id"),
            inverseJoinColumns = @JoinColumn(name = "report_field_id")
    )
    private List<ReportTypeFieldEntity> mappingReportTypes = new ArrayList<>();

    private boolean accumulated; // Accumulated at all or is it checked period by period
    private boolean accumulatedYearly; // Is it accumulated Yearly and only taking care of the current year
    private boolean accumulatedPreviousYear; // Is it accumulated Yearly and only taking care of the previous year

    @ManyToOne
    @JoinColumn(name = "report_id", nullable = false)
    private ReportTypeEntity report;

}
