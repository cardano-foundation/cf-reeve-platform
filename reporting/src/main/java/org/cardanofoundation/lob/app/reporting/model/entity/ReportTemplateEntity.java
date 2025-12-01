package org.cardanofoundation.lob.app.reporting.model.entity;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.support.crypto.SHA3;
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
    private String id;

    private String organisationId;
    private String name;
    private String description;

    @Enumerated(EnumType.STRING)
    private ReportTemplateType reportTemplateType;

    @Builder.Default
    private long ver = 1;

    @Builder.Default
    private boolean active = true;

    @OneToMany(mappedBy = "reportTemplate", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ReportTemplateFieldEntity> fields = new ArrayList<>();

    @OneToMany(mappedBy = "reportTemplate", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ReportTemplateValidationRuleEntity> validationRules = new ArrayList<>();

    @PrePersist
    private void generateId() {
        if (this.id == null) {
            String hashInput = String.format("%s:%s:%s:%s",
                organisationId,
                name,
                reportTemplateType,
                ver
            );
            this.id = SHA3.digestAsHex(hashInput);
        }
    }
}
