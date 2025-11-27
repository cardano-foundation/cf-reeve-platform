package org.cardanofoundation.lob.app.reporting.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Report template data transfer object for creating or updating templates")
public class ReportTemplateDto extends BaseRequest {

    @Schema(description = "Template name", example = "Quarterly Financial Report Template", required = true)
    @NotNull(message = "Template name must not be null")
    private String name;

    @Schema(description = "Template Version", example = "1", required = true)
    private Long ver;

    @Schema(description = "Report template type", example = "BALANCE_SHEET", required = true)
    @NotNull(message = "Report template type must not be null")
    private String reportTemplateType;

    @Schema(description = "Template description", example = "Standard quarterly financial report with balance sheet", nullable = true)
    private String description;

    @Schema(description = "List of template fields defining the report structure", required = true)
    private List<ReportTemplateFieldDto> fields;
}
