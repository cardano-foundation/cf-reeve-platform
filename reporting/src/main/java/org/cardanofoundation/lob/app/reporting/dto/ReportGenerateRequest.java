package org.cardanofoundation.lob.app.reporting.dto;

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
@Schema(description = "Request to generate a report with automatic field calculation (data mode is always GENERATED)")
public class ReportGenerateRequest {

    @NotNull
    @Schema(description = "Organisation ID", example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94", required = true)
    private String organisationId;

    @NotNull
    @Schema(description = "Report template ID (hash-based)", example = "a7b8c9d0e1f2g3h4i5j6k7l8m9n0o1p2q3r4s5t6u7v8w9x0y1z2", required = true)
    private String reportTemplateId;

    @NotNull
    @Schema(description = "Interval type for the report", example = "MONTH", allowableValues = {"MONTH", "QUARTER", "YEAR"}, required = true)
    private String intervalType;

    @NotNull
    @Schema(description = "Year for the report", example = "2024", required = true)
    private Short year;

    @Schema(description = "Period number (1-12 for MONTH, 1-4 for QUARTER, null for YEAR)", example = "3", nullable = true)
    private Short period;

    @NotNull
    @Schema(description = "Indicates whether the report is generated in preview mode", example = "false", required = true)
    private boolean preview = false;
}
