package org.cardanofoundation.lob.app.organisation.domain.entity;

import static org.cardanofoundation.lob.app.support.crypto.SHA3.digestAsHex;

import java.util.Optional;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import io.hypersistence.utils.hibernate.type.basic.PostgreSQLCITextType;
import org.hibernate.annotations.Type;
import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "organisation")
@Audited
@EntityListeners({ AuditingEntityListener.class })
public class Organisation extends CommonEntity implements Persistable<String> {

    @Id
    @Column(name = "organisation_id", nullable = false)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "city", nullable = false)
    private String city;

    @Column(name = "post_code", nullable = false)
    private String postCode;

    @Column(name = "province", nullable = false)
    private String province;

    @Column(name = "address", nullable = false)
    private String address;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "tax_id_number", nullable = false)
    private String taxIdNumber;

    @Column(name = "country_code", nullable = false)
    private String countryCode;

    @Column(name = "dummy_account")
    private String dummyAccount;

    @Column(name = "pre_approve_transactions")
    private Boolean preApproveTransactions;

    @Column(name = "pre_approve_transactions_dispatch")
    private Boolean preApproveTransactionsDispatch;

    @Column(name = "accounting_period_days", nullable = false)
    private int accountPeriodDays; // how many days in the past from yesterday

    @Column(name = "currency_id", nullable = false)
    private String currencyId;

    @Column(name = "report_currency_id", nullable = false)
    private String reportCurrencyId;

    @Column(name = "website_url")
    private String websiteUrl;

    @Column(name = "admin_email", nullable = false)
    private String adminEmail;

    @Lob
    @Type(PostgreSQLCITextType.class)
    @Column(name = "logo")
    private String logo;

    public static String id(String countryCode, String taxIdNumber) {
        return digestAsHex("%s::%s".formatted(
                countryCode, taxIdNumber
        ));
    }

    public boolean isPreApproveTransactionsEnabled() {
        return Optional.ofNullable(preApproveTransactions).orElse(false);
    }

    public boolean isPreApproveTransactionsDispatchEnabled() {
        return Optional.ofNullable(preApproveTransactionsDispatch).orElse(false);
    }

    public Optional<String> getDummyAccount() {
        return Optional.ofNullable(dummyAccount);
    }

}
