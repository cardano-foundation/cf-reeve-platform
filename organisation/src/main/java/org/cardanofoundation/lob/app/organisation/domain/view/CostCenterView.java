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

    private String name;

    private CostCenterView parentCustomerCode;

    private boolean active;

    private Problem error;

    public static CostCenterView fromEntity(CostCenter costCenter) {
        CostCenterViewBuilder builder = CostCenterView.builder()
                .customerCode(costCenter.getId() == null ? null : costCenter.getId().getCustomerCode())
                .name(costCenter.getName())
                .active(costCenter.isActive());
        if (costCenter.getParent().isPresent()) {
            builder.parentCustomerCode(CostCenterView.fromEntity(costCenter.getParent().get()));
        }
        return builder.build();
    }

    public static CostCenterView createFail(CostCenterUpdate costCenterUpdate, Problem error) {
        return CostCenterView.builder()
                .customerCode(costCenterUpdate.getCustomerCode())
                .name(costCenterUpdate.getName())
                .active(costCenterUpdate.isActive())
                .error(error)
                .build();
    }
}
