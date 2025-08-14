package org.cardanofoundation.lob.app.organisation.domain.entity;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.*;

import lombok.*;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import org.hibernate.annotations.BatchSize;
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
public class CostCenter extends CommonEntity implements Persistable<CostCenter.Id> {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "organisationId", column = @Column(name = "organisation_id")),
            @AttributeOverride(name = "customerCode", column = @Column(name = "customer_code"))
    })
    private Id id;

    @Column(name = "name", nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "organisation_id", referencedColumnName = "organisation_id", insertable = false, updatable = false),
            @JoinColumn(name = "parent_customer_code", referencedColumnName = "customer_code", insertable = false, updatable = false)
    })
    @NotAudited
    @JsonBackReference  // Prevents infinite recursion in JSON serialization
    private CostCenter parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    @JsonManagedReference  // Manages the forward reference in JSON serialization
    @BatchSize(size = 100)
    private Set<CostCenter> children = new HashSet<>();

    @Column(name = "parent_customer_code")
    private String parentCustomerCode;

    @Column(name = "active")
    @Builder.Default
    private boolean active = true;

    public Optional<CostCenter> getParent() {
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
