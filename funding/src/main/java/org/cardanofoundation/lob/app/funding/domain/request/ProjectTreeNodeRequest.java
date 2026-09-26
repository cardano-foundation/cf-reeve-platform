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

    @Nullable
    @Schema(example = "WP-1", description = "Optional and ignored: no longer used for lookups or id generation. Still accepted, and still returned in responses, so existing clients keep working.")
    private String externalProjectId;

    @NotBlank
    @Schema(example = "Work Package 1")
    private String projectTitle;

    @Nullable
    @Schema(example = "GRANT-2025-001-WP1", description = "Optional funding reference. Unique per organisation — no two projects may share it.")
    private String fundingId;

    @Nullable
    @Schema(example = "PRJ-1000-S1", description = "Permanent identifier assigned when this sub-project was created "
            + "(see ProjectView#proId). When supplied on an update, matches the existing sub-project by it directly — "
            + "the reliable way to reference one that may have since been renamed. When omitted, falls back to "
            + "matching by the current projectTitle. Ignored on create (a sub-project's proId is always "
            + "system-assigned) unless no existing sub-project matches, in which case a new one is created and this "
            + "value, if present, is used as its proId as-is instead of the usual auto-assigned one.")
    private String proId;

    @Nullable
    @Schema(example = "100000.00", description = "Must be > 0 when set, and not exceed the parent's total.")
    private BigDecimal totalAmount;

    @Nullable
    @Schema(example = "USD")
    private String currency;

    @Nullable
    @Schema(example = "DELETE", description = "When set to \"DELETE\", this sub-project (matched by proId, "
            + "falling back to projectTitle, same as for an update) and its entire subtree — every nested "
            + "sub-project and milestone below it, regardless of what this node's own milestones/subProjects "
            + "lists contain — are deleted instead of created/updated. All other fields on this node are "
            + "ignored. Omit, or leave null, for the normal create-or-update behavior.")
    private String action;

    /** Milestones of this node — mutually exclusive with {@code subProjects}. */
    @Builder.Default
    @Valid
    private List<MilestoneCreateRequest> milestones = new ArrayList<>();

    /** Sub-projects of this node — mutually exclusive with {@code milestones}. */
    @Builder.Default
    @Valid
    private List<ProjectTreeNodeRequest> subProjects = new ArrayList<>();

}
