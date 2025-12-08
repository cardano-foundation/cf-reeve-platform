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

    @Schema(description = "Unique template ID (SHA3-256 hash)", example = "a7b8c9d0e1f2g3h4i5j6k7l8m9n0o1p2q3r4s5t6u7v8w9x0y1z2", required = true)
    private String id;

    @Schema(description = "Template name", example = "Quarterly Financial Report Template", required = true)
    @NotNull(message = "Template name must not be null")
    private String name;

    @Schema(description = "Template Version", example = "1", required = true)
    private Long ver;

    @Schema(description = "Report template type", example = "BALANCE_SHEET", required = true)
    @NotNull(message = "Report template type must not be null")
    private String reportTemplateType;

    @Schema(description = "Data mode for the report. Options are: SYSTEM, USER", example = "SYSTEM", required = true)
    @NotNull(message = "Data mode must not be null")
    private String dataMode;

    @Schema(description = "Template description", example = "Standard quarterly financial report with balance sheet", nullable = true)
    private String description;

    @Schema(description = "Whether the template is active", example = "true", defaultValue = "true")
    @Builder.Default
    private boolean active = true;

    @Schema(description = "List of template fields defining the report structure", required = true)
    private List<ReportTemplateFieldDto> fields;

    @Schema(description = "List of validation rules for report fields", nullable = true)
    private List<ValidationRuleDto> validationRules;
}
