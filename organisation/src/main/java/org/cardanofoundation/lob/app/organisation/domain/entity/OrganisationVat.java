package org.cardanofoundation.lob.app.organisation.domain.entity;

import java.math.BigDecimal;

import jakarta.persistence.*;

import lombok.*;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.lang.Nullable;

import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "organisation_vat")
@Audited
@Builder
@EntityListeners({AuditingEntityListener.class})
public class OrganisationVat extends CommonEntity implements Persistable<OrganisationVat.Id> {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "organisationId", column = @Column(name = "organisation_id")),
            @AttributeOverride(name = "customerCode", column = @Column(name = "customer_code"))
    })
    private Id id;

    @Column(name = "rate", nullable = false)
    private BigDecimal rate;

    @Column(name = "description")
    private String description;

    @Column(name = "parent_organisation_vat")
    @Nullable
    private String parentOrganisationVat;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Embeddable
    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @EqualsAndHashCode
    public static class Id {

        private String organisationId;
        private String customerCode;

    }

}
