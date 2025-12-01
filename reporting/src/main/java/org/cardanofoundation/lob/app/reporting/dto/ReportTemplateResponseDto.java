package org.cardanofoundation.lob.app.reporting.dto;

import java.util.List;
import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Report template response containing all template details")
public class ReportTemplateResponseDto {

    @Schema(description = "Unique template ID (SHA3-256 hash)", example = "a7b8c9d0e1f2g3h4i5j6k7l8m9n0o1p2q3r4s5t6u7v8w9x0y1z2", required = true)
    private String id;

    @Schema(description = "Organisation ID", example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94", required = true)
    private String organisationId;

    @Schema(description = "Template name", example = "Quarterly Financial Report Template", required = true)
    private String name;

    @Schema(description = "Template description", example = "Standard quarterly financial report with balance sheet", nullable = true)
    private String description;

    @Schema(description = "Report template type", example = "BALANCE_SHEET", required = true)
    private ReportTemplateType reportTemplateType;

    @Schema(description = "Version number for optimistic locking", example = "1")
    private Long ver;

    @Schema(description = "Whether the template is active", example = "true")
    private Boolean active;

    @Schema(description = "List of template field definitions")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<ReportTemplateFieldDto> columns;

    @Schema(description = "List of validation rules for report fields")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<ValidationRuleDto> validationRules;

    private Optional<Problem> error;

}
