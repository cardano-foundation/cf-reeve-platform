package org.cardanofoundation.lob.app.reporting.model.entity;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "report_standard")
@Audited
@EntityListeners({ AuditingEntityListener.class })
public class ReportStandardEntity extends CommonEntity implements Persistable<ReportStandardEntity.Id> {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "organisationId", column = @Column(name = "organisation_id")),
            @AttributeOverride(name = "code", column = @Column(name = "code"))
    })
    @NonNull
    private Id id;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "active")
    private boolean active = true;

    @Embeddable
    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @EqualsAndHashCode
    @Audited
    public static class Id {

        private String organisationId;
        private String code;

    }

}
