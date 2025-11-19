package org.cardanofoundation.lob.app.reporting.dto;

import jakarta.validation.constraints.NotNull;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportGenerateRequest {

    @NotNull
    @Schema(description = "Organisation ID", example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94", required = true)
    private String organisationId;

    @NotNull
    @Schema(description = "Report template ID", example = "1", required = true)
    private Long reportTemplateId;

    @NotNull
    @Schema(description = "Interval type for the report", example = "MONTHLY", allowableValues = {"MONTHLY", "QUARTERLY", "YEARLY"}, required = true)
    private String intervalType;

    @NotNull
    @Schema(description = "Year for the report", example = "2024", required = true)
    private Short year;

    @Nullable
    @Schema(description = "Period (e.g., month or quarter number). Required for MONTHLY and QUARTERLY intervals", example = "3")
    private Short period;
}
