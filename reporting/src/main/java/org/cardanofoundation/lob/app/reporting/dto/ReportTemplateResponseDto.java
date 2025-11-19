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
public class ReportTemplateResponseDto {

    private Long id;

    private String organisationId;

    private String name;

    private String description;

    private String currencyId;

    private Long ver;

    private List<ReportTemplateFieldDto> columns;
}
