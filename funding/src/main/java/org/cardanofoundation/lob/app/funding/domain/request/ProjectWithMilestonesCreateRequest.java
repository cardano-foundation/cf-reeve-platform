package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.*;

import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ProjectWithMilestonesCreateRequest {

    @NotBlank
    @Schema(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
    private String organisationId;

    @NotBlank
    @Schema(example = "GRANT-2025-001")
    private String fundingId;

    @NotBlank
    @Schema(example = "PROJ-AB")
    private String projectId;

    @NotBlank
    @Schema(example = "Project AB")
    private String projectTitle;

    @NotNull
    @Schema(example = "200000.00")
    private BigDecimal totalAmount;

    @NotBlank
    @Schema(example = "USD")
    private String currency;

    @Builder.Default
    @Valid
    @Schema(description = "Milestones to create together with the project")
    private List<MilestoneCreateRequest> milestones = new ArrayList<>();

}
