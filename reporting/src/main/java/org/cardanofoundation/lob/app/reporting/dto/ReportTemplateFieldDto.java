package org.cardanofoundation.lob.app.reporting.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportTemplateFieldDto {

    private Long id;
    private String fieldName;
    private boolean accumulated;
    private boolean accumulatedYearly;
    private boolean accumulatedPreviousYear;
    private boolean negated;
    private List<Long> mappingSubTypeIds;

    private List<ReportTemplateFieldDto> childFields;
}
