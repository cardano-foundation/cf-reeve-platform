package org.cardanofoundation.lob.app.organisation.domain.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Entity
@Table(name = "organisation_event_code")
@Builder
@Audited
@EntityListeners({ AuditingEntityListener.class })
public class EventCode extends CommonEntity implements Persistable<EventCode.Id> {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "organisationId", column = @Column(name = "organisation_id")),
            @AttributeOverride(name = "debitReferenceCode", column = @Column(name = "debit_reference_code")),
            @AttributeOverride(name = "creditReferenceCode", column = @Column(name = "credit_reference_code"))
    })
    private Id id;

    private String name;

    @Embeddable
    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @EqualsAndHashCode
    public static class Id {

        private String organisationId;
        // ReferenceCode //
        private String debitReferenceCode;
        // ReferenceCode //
        private String creditReferenceCode;

    }
}
