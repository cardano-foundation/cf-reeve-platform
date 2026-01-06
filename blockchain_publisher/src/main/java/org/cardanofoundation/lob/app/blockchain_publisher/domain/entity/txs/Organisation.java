package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs;

import jakarta.persistence.Embeddable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@AllArgsConstructor
@Getter
@NoArgsConstructor
@Setter
@Builder
public class Organisation {

    private String id;

    private String name;

    private String taxIdNumber;

    private String countryCode;

    private String currencyId;

    public static Organisation fromOrganisationEntity(
            org.cardanofoundation.lob.app.organisation.domain.entity.Organisation organisation) {
        return Organisation.builder()
                .id(organisation.getId())
                .name(organisation.getName())
                .taxIdNumber(organisation.getTaxIdNumber())
                .countryCode(organisation.getCountryCode())
                .currencyId(organisation.getCurrencyId())
                .build();
    }

}
