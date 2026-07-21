package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import lombok.*;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A project node in a create-tree request. A node has <em>either</em> milestones <em>or</em>
 * sub-projects (never both). The organisation is inherited from the root request.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ProjectTreeNodeRequest {

    @NotBlank
    @Schema(example = "WP-1", description = "User-defined id, unique within its parent.")
    private String externalProjectId;

    @NotBlank
    @Schema(example = "Work Package 1")
    private String projectTitle;

    @Nullable
    @Schema(example = "GRANT-2025-001-WP1", description = "Optional funding reference. Unique per organisation — no two projects may share it.")
    private String fundingId;

    @Nullable
    @Schema(example = "100000.00", description = "Must be > 0 when set, and not exceed the parent's total.")
    private BigDecimal totalAmount;

    @Nullable
    @Schema(example = "USD")
    private String currency;

    /** Milestones of this node — mutually exclusive with {@code subProjects}. */
    @Builder.Default
    @Valid
    private List<MilestoneCreateRequest> milestones = new ArrayList<>();

    /** Sub-projects of this node — mutually exclusive with {@code milestones}. */
    @Builder.Default
    @Valid
    private List<ProjectTreeNodeRequest> subProjects = new ArrayList<>();

}
