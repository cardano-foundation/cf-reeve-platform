package org.cardanofoundation.lob.app.organisation.domain.entity;

import java.util.Optional;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.*;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "organisation_ref_codes")
@Builder
@Audited
@EntityListeners({ AuditingEntityListener.class })
public class ReferenceCode extends CommonEntity implements Persistable<ReferenceCode.Id> {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "organisationId", column = @Column(name = "organisation_id")),
            @AttributeOverride(name = "referenceCode", column = @Column(name = "reference_code"))
    })
    private Id id;

    @ManyToOne
    @JoinColumns({
            @JoinColumn(name = "organisation_id", referencedColumnName = "organisation_id", insertable = false, updatable = false),
            @JoinColumn(name = "parent_reference_code", referencedColumnName = "reference_code", insertable = false, updatable = false)
    })
    @NotAudited
    private ReferenceCode parent;

    @Column(name = "parent_reference_code")
    private String parentReferenceCode;

    private String name;

    @Column(name = "active")
    private boolean isActive = true;

    @Embeddable
    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @EqualsAndHashCode
    public static class Id {

        private String organisationId;
        private String referenceCode;

    }

    public Optional<ReferenceCode> getParent() {
        return Optional.ofNullable(parent);
    }
}
