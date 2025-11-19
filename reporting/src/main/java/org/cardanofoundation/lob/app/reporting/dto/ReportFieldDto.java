package org.cardanofoundation.lob.app.reporting.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportFieldDto {

    private Long templateFieldId;

    private String templateFieldName;

    private BigDecimal value;

    private List<ReportFieldDto> childFields;
}
