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
 * A sub-project node inside an event allocation — the same recursive shape as the create-project
 * endpoint's sub-project tree, except each milestone carries an {@code allocatedAmount}. A node holds
 * <em>either</em> milestones <em>or</em> sub-projects (never both). Existing sub-projects are matched
 * by {@code projectTitle} within the parent; otherwise a new sub-project is created.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class EventSubProjectAllocationRequest {

    @NotBlank
    @Schema(example = "Sub1", description = "User-defined sub-project id. No longer used to match an existing sub-project — matched by projectTitle instead. Accepted and stored for backward compatibility only.")
    private String externalProjectId;

    @Nullable
    @Schema(example = "Project leaf 1", description = "Required. Matches an existing sub-project by (parentProjectId, projectTitle), or names a new one.")
    private String projectTitle;

    @Nullable
    @Schema(example = "GRANT-2025-001-SUB1", description = "Optional funding reference of the sub-project. Unique per organisation — no two projects may share it.")
    private String fundingId;

    @Nullable
    @Schema(example = "100000.00", description = "Optional budget; when set must be > 0 and fit within the parent's total.")
    private BigDecimal totalAmount;

    @Nullable
    @Schema(example = "USD")
    private String currency;

    /** Milestone allocations for this node — mutually exclusive with {@code subProjects}. */
    @Builder.Default
    @Valid
    private List<EventMilestoneAllocationRequest> milestones = new ArrayList<>();

    /** Sub-projects of this node — mutually exclusive with {@code milestones}. */
    @Builder.Default
    @Valid
    private List<EventSubProjectAllocationRequest> subProjects = new ArrayList<>();

}
