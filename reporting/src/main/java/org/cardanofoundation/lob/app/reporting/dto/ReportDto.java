package org.cardanofoundation.lob.app.reporting.dto;

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
@Schema(description = "Report data transfer object for creating or updating reports")
public class ReportDto {

    @Schema(description = "Organisation ID", example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94", required = true)
    private String organisationId;

    @Schema(description = "Report template ID (hash-based)", example = "a7b8c9d0e1f2g3h4i5j6k7l8m9n0o1p2q3r4s5t6u7v8w9x0y1z2", required = true)
    private String reportTemplateId;

    @Schema(description = "Report name", example = "Q1 2024 Financial Report", required = true)
    private String name;

    @Schema(description = "Interval type", example = "QUARTER", allowableValues = {"MONTH", "QUARTER", "YEAR"}, required = true)
    private String intervalType;

    @Schema(description = "Period number (1-12 for MONTH, 1-4 for QUARTER, null for YEAR)", example = "1", nullable = true)
    private Short period;

    @Schema(description = "Year for the report", example = "2024", required = true)
    private Short year;

    @NotNull(message = "Data mode is required (SYSTEM or USER)")
    @Schema(description = "Data mode indicating how the report data is populated", example = "SYSTEM", allowableValues = {"SYSTEM", "USER"}, required = true)
    private String dataMode;

    @Schema(description = "List of report fields with values (only for USER data mode)", nullable = true)
    private List<ReportFieldDto> fields;
}
