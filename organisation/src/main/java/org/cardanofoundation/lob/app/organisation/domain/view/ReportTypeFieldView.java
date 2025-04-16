package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeFieldEntity;

@Getter
@Builder
@AllArgsConstructor
public class ReportTypeFieldView {

    private Long id;
    private String name;
    private List<ReportTypeFieldView> fields;
    private List<OrganisationChartOfAccountSubTypeView> mappings;
    private boolean accumulated;
    private boolean accumulatedYearly;

    public static List<ReportTypeFieldView> fromEntities(List<ReportTypeFieldEntity> fields) {
        return fields.stream()
                .map(ReportTypeFieldView::fromEntity)
                .toList();
    }

    private static ReportTypeFieldView fromEntity(ReportTypeFieldEntity reportTypeFieldEntity) {
        return ReportTypeFieldView.builder()
                .id(reportTypeFieldEntity.getId())
                .name(reportTypeFieldEntity.getName())
                .fields(fromEntities(reportTypeFieldEntity.getChildFields()))
                .mappings(OrganisationChartOfAccountSubTypeView.fromEntities(reportTypeFieldEntity.getMappingTypes()))
                .accumulated(reportTypeFieldEntity.isAccumulated())
                .accumulatedYearly(reportTypeFieldEntity.isAccumulatedYearly())
                .build();
    }
}
