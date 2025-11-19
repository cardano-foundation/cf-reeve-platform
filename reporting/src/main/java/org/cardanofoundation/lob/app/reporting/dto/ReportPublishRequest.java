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
@Schema(description = "Request to publish a report to the blockchain")
public class ReportPublishRequest {

    @NotNull
    @Schema(description = "Organisation ID", example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94", required = true)
    private String organisationId;

    @NotNull
    @Schema(description = "Report ID to publish", example = "b1c2d3e4f5g6h7i8j9k0l1m2n3o4p5q6r7s8t9u0v1w2x3y4z5a6b7c8d9e0f1g2", required = true)
    private String reportId;

}
