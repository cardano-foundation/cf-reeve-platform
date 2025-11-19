package org.cardanofoundation.lob.app.reporting.model.entity;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@Entity
@Table(name = "report_template")
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
public class ReportTemplateEntity extends CommonEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String organisationId;
    private String name;
    private String description;
    private String currencyId;

    @OneToMany(mappedBy = "reportTemplate", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ReportTemplateFieldEntity> columns = new ArrayList<>();
}
