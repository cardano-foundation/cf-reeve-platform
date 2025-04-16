package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeEntity;

@Getter
@Builder
@AllArgsConstructor
public class ReportTypeView {

    private Long id;
    private String organisationId;
    private String name;
    private List<ReportTypeFieldView> fields;

    public static ReportTypeView fromEntity(ReportTypeEntity reportTypeEntity) {
        return ReportTypeView.builder()
                .id(reportTypeEntity.getId())
                .organisationId(reportTypeEntity.getOrganisationId())
                .name(reportTypeEntity.getName())
                .fields(ReportTypeFieldView.fromEntities(reportTypeEntity.getFields()))
                .build();
    }
}
