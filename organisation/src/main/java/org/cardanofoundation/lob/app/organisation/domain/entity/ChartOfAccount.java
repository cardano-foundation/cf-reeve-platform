package org.cardanofoundation.lob.app.organisation.domain.entity;

import jakarta.persistence.*;

import javax.annotation.Nullable;

import lombok.*;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "organisation_chart_of_account")
@Audited
@Builder
@EntityListeners({AuditingEntityListener.class})
public class ChartOfAccount extends CommonEntity implements Persistable<ChartOfAccount.Id> {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "organisationId", column = @Column(name = "organisation_id")),
            @AttributeOverride(name = "customerCode", column = @Column(name = "customer_code"))
    })
    private Id id;

    @Column(name = "event_ref_code", nullable = false)
    private String eventRefCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "currency_id", nullable = false)
    private String currencyId;

    @Column(name = "counter_party", nullable = false)
    private String counterParty;

    @Column(name = "parent_customer_code")
    private String parentCustomerCode;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "subType", referencedColumnName = "id")
    private ChartOfAccountSubType subType;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "balanceFCY", column = @Column(name = "opening_balance_balance_fcy")),
            @AttributeOverride(name = "balanceLCY", column = @Column(name = "opening_balance_balance_lcy")),
            @AttributeOverride(name = "originalCurrencyIdFCY", column = @Column(name = "opening_balance_original_currency_id_fcy")),
            @AttributeOverride(name = "originalCurrencyIdLCY", column = @Column(name = "opening_balance_original_currency_id_lcy")),
            @AttributeOverride(name = "balanceType", column = @Column(name = "opening_balance_balance_type")),
            @AttributeOverride(name = "date", column = @Column(name = "opening_balance_date"))

    })
    @Getter
    @Setter
    @Nullable
    private OpeningBalance openingBalance;

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
