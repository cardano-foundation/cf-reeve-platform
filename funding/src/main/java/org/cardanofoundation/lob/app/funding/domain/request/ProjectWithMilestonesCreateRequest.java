package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.*;
import lombok.experimental.SuperBuilder;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class ProjectWithMilestonesCreateRequest extends BaseRequest {

    @NotBlank
    @Schema(example = "GRANT-2025-001")
    private String fundingId;

    @NotBlank
    @Schema(example = "PROJ-AB")
    private String externalProjectId;

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
