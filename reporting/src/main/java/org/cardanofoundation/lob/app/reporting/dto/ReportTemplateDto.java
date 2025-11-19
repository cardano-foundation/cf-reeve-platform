package org.cardanofoundation.lob.app.reporting.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Report template data transfer object for creating or updating templates")
public class ReportTemplateDto {

    @Schema(description = "Organisation ID", example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94", required = true)
    private String organisationId;

    @Schema(description = "Template name", example = "Quarterly Financial Report Template", required = true)
    private String name;

    @Schema(description = "Report template type", example = "BALANCE_SHEET", required = true)
    private ReportTemplateType reportTemplateType;

    @Schema(description = "Template description", example = "Standard quarterly financial report with balance sheet", nullable = true)
    private String description;

    @Schema(description = "List of template fields defining the report structure", required = true)
    private List<ReportTemplateFieldDto> fields;
}
