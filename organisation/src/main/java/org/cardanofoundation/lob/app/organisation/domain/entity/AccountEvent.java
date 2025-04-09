package org.cardanofoundation.lob.app.organisation.domain.entity;

import static java.lang.Boolean.TRUE;

import jakarta.persistence.*;

import lombok.*;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.envers.Audited;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "organisation_account_event")
@Builder
@Audited
@EntityListeners({ AuditingEntityListener.class })
@ToString
public class AccountEvent {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "organisationId", column = @Column(name = "organisation_id")),
            @AttributeOverride(name = "debitReferenceCode", column = @Column(name = "debit_reference_code")),
            @AttributeOverride(name = "creditReferenceCode", column = @Column(name = "credit_reference_code"))
    })
    private Id id;

    @Column(nullable = false)
    private String name;

    private Boolean active = TRUE;

    @Getter
    @Column(name = "customer_code", nullable = false)
    private String customerCode;

    @Embeddable
    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @EqualsAndHashCode
    public static class Id {

        private String organisationId;
        private String debitReferenceCode;
        private String creditReferenceCode;

    }


}
