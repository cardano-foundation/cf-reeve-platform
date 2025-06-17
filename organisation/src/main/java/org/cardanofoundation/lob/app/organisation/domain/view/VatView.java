package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationVat;



@Getter
@Builder
@AllArgsConstructor
public class VatView {

    private String organisationId;
    private String customerCode;
    private String rate;
    private String countryCode;
    private String description;
    private Boolean active;

    private Problem error;

    public Optional<Problem> getError() {
        return Optional.ofNullable(error);
    }


    public static VatView convertFromEntity(OrganisationVat organisationVat) {
        return VatView.builder()
                .customerCode(organisationVat.getId().getCustomerCode())
                .organisationId(organisationVat.getId().getOrganisationId())
                .rate(organisationVat.getRate().toString())
                .countryCode(organisationVat.getCountryCode())
                .description(organisationVat.getDescription())
                .active(organisationVat.getActive())
                .build();
    }

    public static VatView createFail(String customerCode, Problem error) {
        return VatView.builder()
                .customerCode(customerCode)
                //.name(error.getTitle())
                //.subType(chartOfAccount.getSubType().getId())
                //.type(chartOfAccount.getSubType().getType().getId())
                .error(error)
                .build();
    }
}
