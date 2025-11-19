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
public class ReportDto {

    private String organisationId;

    private Long reportTemplateId;

    private String name;

    private String intervalType;

    private Short period;

    private Short year;

    private List<ReportFieldDto> fields;
}
