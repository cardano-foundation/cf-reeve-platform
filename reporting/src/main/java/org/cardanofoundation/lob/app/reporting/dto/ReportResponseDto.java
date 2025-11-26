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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Report response containing all report details")
public class ReportResponseDto {

    @Schema(description = "Unique report ID (SHA3-256 hash)", example = "b1c2d3e4f5g6h7i8j9k0l1m2n3o4p5q6r7s8t9u0v1w2x3y4z5a6b7c8d9e0f1g2", required = true)
    private String id;

    @Schema(description = "Organisation ID", example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94", required = true)
    private String organisationId;

    @Schema(description = "Report template ID", example = "a7b8c9d0e1f2g3h4i5j6k7l8m9n0o1p2q3r4s5t6u7v8w9x0y1z2", required = true)
    private String reportTemplateId;

    @Schema(description = "Report name", example = "Q1 2024 Financial Report", required = true)
    private String name;

    @Schema(description = "Interval type", example = "QUARTER", allowableValues = {"MONTH", "QUARTER", "YEAR"})
    private String intervalType;

    @Schema(description = "Period number", example = "1", nullable = true)
    private Short period;

    @Schema(description = "Year", example = "2024")
    private Short year;

    @Schema(description = "Version number for optimistic locking", example = "1")
    private Long ver;

    @Schema(description = "Data mode", example = "SYSTEM", allowableValues = {"SYSTEM", "USER"})
    private String dataMode;

    @Schema(description = "Indicates if the report is ready to be published to blockchain", example = "true")
    private Boolean isReadyToPublish;

    @Schema(description = "Publish error if validation failed", example = "MISSING_REQUIRED_FIELDS", nullable = true)
    private String publishError;

    @Schema(description = "Indicates if the report has been published to blockchain", example = "false")
    private Boolean isPublished;

    @Schema(description = "Blockchain transaction ID if published", example = "4f5e6d7c8b9a0b1c2d3e4f5g6h7i8j9k0l1m2n3o4p5q6r7s8t9u0v1w2x3y4z5a6", nullable = true)
    private String blockchainTxId;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "List of report fields with calculated or user-provided values")
    private List<ReportFieldDto> fields;

    private Optional<Problem> error;
}
