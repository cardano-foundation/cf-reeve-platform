package org.cardanofoundation.lob.app.organisation.domain.view;


import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccountSubType;


@Getter
@Builder
@AllArgsConstructor
public class OrganisationChartOfAccountSubTypeView {

    private Long id;

    private String organisationId;

    private String name;

    @Builder.Default
    private Set<OrganisationChartOfAccountView> chartOfAccounts = new LinkedHashSet<>();

    public static List<OrganisationChartOfAccountSubTypeView> fromEntities(List<OrganisationChartOfAccountSubType> mappingTypes) {
        return mappingTypes.stream()
                .map(OrganisationChartOfAccountSubTypeView::fromEntity)
                .toList();
    }

    private static OrganisationChartOfAccountSubTypeView fromEntity(OrganisationChartOfAccountSubType organisationChartOfAccountSubType) {
        return OrganisationChartOfAccountSubTypeView.builder()
                .id(organisationChartOfAccountSubType.getId())
                .organisationId(organisationChartOfAccountSubType.getOrganisationId())
                .name(organisationChartOfAccountSubType.getName())
                .build();
    }
}
