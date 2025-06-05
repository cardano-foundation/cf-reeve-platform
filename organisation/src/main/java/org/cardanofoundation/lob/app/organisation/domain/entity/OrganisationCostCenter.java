package org.cardanofoundation.lob.app.organisation.domain.entity;

import java.util.Optional;

import jakarta.persistence.*;

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
@Table(name = "organisation_cost_center")
@Builder
@Audited
@EntityListeners({ AuditingEntityListener.class })
public class OrganisationCostCenter extends CommonEntity implements Persistable<OrganisationCostCenter.Id> {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "organisationId", column = @Column(name = "organisation_id")),
            @AttributeOverride(name = "customerCode", column = @Column(name = "customer_code"))
    })
    private Id id;

    @Column(name = "external_customer_code", nullable = false)
    private String externalCustomerCode;

    @Column(name = "name", nullable = false)
    private String name;

    @ManyToOne
    @JoinColumns({
            @JoinColumn(name = "organisation_id", referencedColumnName = "organisation_id", insertable = false, updatable = false),
            @JoinColumn(name = "parent_customer_code", referencedColumnName = "customer_code", insertable = false, updatable = false)
    })
    @NotAudited
    private OrganisationCostCenter parent;

    @Column(name = "parent_customer_code")
    private String parentCustomerCode;

    @Column(name = "active")
    @Builder.Default
    private boolean active = true;

    public Optional<OrganisationCostCenter> getParent() {
        return Optional.ofNullable(parent);
    }

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
