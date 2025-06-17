package org.cardanofoundation.lob.app.organisation.domain.view;


import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountSubType;


@Getter
@Builder
@AllArgsConstructor
public class ChartOfAccountSubTypeView {

    private Long id;

    private String organisationId;

    private String name;

    @Builder.Default
    private Set<ChartOfAccountView> chartOfAccounts = new LinkedHashSet<>();

    public static List<ChartOfAccountSubTypeView> fromEntities(List<ChartOfAccountSubType> mappingTypes) {
        return mappingTypes.stream()
                .map(ChartOfAccountSubTypeView::fromEntity)
                .toList();
    }

    private static ChartOfAccountSubTypeView fromEntity(ChartOfAccountSubType chartOfAccountSubType) {
        return ChartOfAccountSubTypeView.builder()
                .id(chartOfAccountSubType.getId())
                .organisationId(chartOfAccountSubType.getOrganisationId())
                .name(chartOfAccountSubType.getName())
                .build();
    }
}
