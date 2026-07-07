package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
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

    @Nullable
    @Schema(example = "GRANT-2025-001", description = "Optional funding reference.")
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

    /** When set, the project is created as a sub-project of this (existing) parent. Null creates a root project. */
    @Nullable
    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa",
            description = "Optional id of an existing parent project. When set, the new project is created as its sub-project.")
    private String parentProjectId;

    @Builder.Default
    @Valid
    @Schema(description = "Milestones to create together with the project. Mutually exclusive with subProjects.")
    private List<MilestoneCreateRequest> milestones = new ArrayList<>();

    @Builder.Default
    @Valid
    @Schema(description = "Sub-projects (each with its own milestones or sub-projects) to create in the same "
            + "atomic call. Mutually exclusive with milestones.")
    private List<ProjectTreeNodeRequest> subProjects = new ArrayList<>();

}
