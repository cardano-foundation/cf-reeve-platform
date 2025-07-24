package org.cardanofoundation.lob.app.organisation.domain.view;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.organisation.domain.csv.CostCenterUpdate;
import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;


@Getter
@Builder
@AllArgsConstructor
public class CostCenterView {

    private String customerCode;

    private String externalCustomerCode;

    private String name;

    private CostCenterView parent;

    private String parentCustomerCode;

    private boolean active;

    private Problem error;

    public static CostCenterView fromEntity(CostCenter costCenter) {
        CostCenterViewBuilder builder = CostCenterView.builder()
                .customerCode(costCenter.getId() == null ? null : costCenter.getId().getCustomerCode())
                .externalCustomerCode(costCenter.getExternalCustomerCode())
                .name(costCenter.getName())
                .active(costCenter.isActive());
        if (costCenter.getParent().isPresent()) {
            builder.parent(CostCenterView.fromEntity(costCenter.getParent().get()));
        }
        return builder.build();
    }

    public static CostCenterView createFail(CostCenterUpdate costCenterUpdate, Problem error) {
        return CostCenterView.builder()
                .customerCode(costCenterUpdate.getCustomerCode())
                .externalCustomerCode(costCenterUpdate.getExternalCustomerCode())
                .name(costCenterUpdate.getName())
                .active(costCenterUpdate.isActive())
                .parentCustomerCode(costCenterUpdate.getParentCustomerCode())
                .error(error)
                .build();
    }
}
