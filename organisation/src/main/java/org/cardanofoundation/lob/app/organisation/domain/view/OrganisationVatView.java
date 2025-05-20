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
public class OrganisationVatView {

    private String organisationId;
    private String customerCode;
    private String rate;
    private String parentOrganisationVat;
    private String description;
    private Boolean active;

    private Optional<Problem> error;


    public static OrganisationVatView convertFromEntity(OrganisationVat organisationVat) {
        return OrganisationVatView.builder()
                .customerCode(organisationVat.getId().getCustomerCode())
                .organisationId(organisationVat.getId().getOrganisationId())
                .rate(organisationVat.getRate().toString())
                .parentOrganisationVat(organisationVat.getParentOrganisationVat())
                .description(organisationVat.getDescription())
                .active(organisationVat.getActive())
                .error(Optional.empty())
                .build();
    }

    public static OrganisationVatView createFail(Problem error) {
        return OrganisationVatView.builder()
                //.name(error.getTitle())
                //.subType(chartOfAccount.getSubType().getId())
                //.type(chartOfAccount.getSubType().getType().getId())
                .error(Optional.of(error))
                .build();
    }
}
