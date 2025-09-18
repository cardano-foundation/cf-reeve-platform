package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

    private CostCenterView parent;

    private Set<CostCenterView> children;

    private String parentCustomerCode;

    private boolean active;

    private Optional<Problem> error;

    public static CostCenterView fromEntity(CostCenter costCenter) {
        CostCenterViewBuilder builder = CostCenterView.builder()
                .customerCode(costCenter.getId() == null ? null : costCenter.getId().getCustomerCode())
                .name(costCenter.getName())
                .active(costCenter.isActive())
                .error(Optional.empty());
        if (costCenter.getParent().isPresent()) {
            builder.parent(CostCenterView.fromEntityWithoutParentAndChildren(costCenter.getParent().get()));
        }
        if(!costCenter.getChildren().isEmpty()) {
            builder.children(costCenter.getChildren().stream().map(CostCenterView::fromEntityWithoutParentAndChildren).collect(Collectors.toSet()));
        }
        return builder.build();
    }

    public static CostCenterView fromEntityWithoutParentAndChildren(CostCenter costCenter) {
        return CostCenterView.builder()
                .customerCode(costCenter.getId() == null ? null : costCenter.getId().getCustomerCode())
                .name(costCenter.getName())
                .active(costCenter.isActive())
                .error(Optional.empty())
                .build();
    }

    public static CostCenterView createFail(CostCenterUpdate costCenterUpdate, Problem error) {
        return CostCenterView.builder()
                .customerCode(costCenterUpdate.getCustomerCode())
                .name(costCenterUpdate.getName())
                .parentCustomerCode(costCenterUpdate.getParentCustomerCode())
                .error(Optional.of(error))
                .build();
    }
}
