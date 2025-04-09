package org.cardanofoundation.lob.app.organisation.domain.view;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationProject;

@Getter
@Builder
@AllArgsConstructor
public class OrganisationProjectView {

    private String customerCode;

    private String externalCustomerCode;

    private String name;

    private OrganisationProjectView parentCustomerCode;

    public static OrganisationProjectView fromEntity(OrganisationProject costCenter) {
        OrganisationProjectView.OrganisationProjectViewBuilder builder = OrganisationProjectView.builder()
                .customerCode(costCenter.getId() == null ? null : costCenter.getId().getCustomerCode())
                .externalCustomerCode(costCenter.getExternalCustomerCode())
                .name(costCenter.getName());
        if (costCenter.getParent().isPresent()) {
            builder.parentCustomerCode(OrganisationProjectView.fromEntity(costCenter.getParent().get()));
        }
        return builder.build();
    }
}
