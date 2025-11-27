package org.cardanofoundation.lob.app.reporting.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Report field containing a value and optional child fields")
public class ReportFieldDto {

    @Schema(description = "Template field ID this field is based on", example = "101", nullable = true)
    @NotNull(message = "Template field ID cannot be null")
    private Long templateFieldId;

    @Schema(description = "Name of the field from template", example = "Total Revenue", nullable = true)
    @NotNull(message = "Template field name cannot be null")
    private String templateFieldName;

    @Schema(description = "Field value", example = "125000.50", nullable = true)
    private BigDecimal value;

    @Schema(description = "Child fields forming a hierarchical structure", nullable = true)
    private List<ReportFieldDto> childFields;
}
