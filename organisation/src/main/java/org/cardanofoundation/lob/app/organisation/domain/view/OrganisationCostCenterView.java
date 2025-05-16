package org.cardanofoundation.lob.app.organisation.domain.view;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationCostCenter;


@Getter
@Builder
@AllArgsConstructor
public class OrganisationCostCenterView {

    private String customerCode;

    private String externalCustomerCode;

    private String name;

    private OrganisationCostCenterView parentCustomerCode;

    private Problem error;

    public static OrganisationCostCenterView fromEntity(OrganisationCostCenter costCenter) {
        OrganisationCostCenterViewBuilder builder = OrganisationCostCenterView.builder()
                .customerCode(costCenter.getId() == null ? null : costCenter.getId().getCustomerCode())
                .externalCustomerCode(costCenter.getExternalCustomerCode())
                .name(costCenter.getName());
        if (costCenter.getParent().isPresent()) {
            builder.parentCustomerCode(OrganisationCostCenterView.fromEntity(costCenter.getParent().get()));
        }
        return builder.build();
    }

    public static OrganisationCostCenterView createFail(String customerCode, Problem error) {
        return OrganisationCostCenterView.builder()
                .customerCode(customerCode)
                .error(error)
                .build();
    }
}
