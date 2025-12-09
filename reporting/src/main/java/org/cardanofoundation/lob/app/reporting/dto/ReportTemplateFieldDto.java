package org.cardanofoundation.lob.app.reporting.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Report template field definition")
public class ReportTemplateFieldDto {

    @Schema(description = "Field name", example = "Total Revenue")
    @NotNull(message = "Field name must not be null")
    private String fieldName;

    @Schema(description = "Whether values should be accumulated", example = "false", defaultValue = "false")
    private boolean accumulated;

    @Schema(description = "Whether values should be accumulated yearly", example = "false", defaultValue = "false")
    private boolean accumulatedYearly;

    @Schema(description = "Whether values from previous year should be accumulated", example = "false", defaultValue = "false")
    private boolean accumulatedPreviousYear;

    @Schema(description = "Whether the value should be negated (for expenses)", example = "false", defaultValue = "false")
    private boolean negated;

    @Schema(description = "List of chart of account subtype IDs to map to this field", example = "[1, 2, 3]", nullable = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<Long> mappingSubTypeIds = new ArrayList<>();

    @Schema(description = "Child fields forming a hierarchical structure", nullable = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<ReportTemplateFieldDto> childFields = new ArrayList<>();
}
