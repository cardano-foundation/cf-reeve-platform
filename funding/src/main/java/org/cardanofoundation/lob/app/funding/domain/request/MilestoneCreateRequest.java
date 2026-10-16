package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.annotation.Nullable;

import lombok.*;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MilestoneCreateRequest {

    @Nullable
    @Schema(example = "MS-1", description = "User-defined milestone ID. No longer used to match an existing milestone — matched by milestoneTitle instead. Accepted and stored for backward compatibility only.")
    private String externalMilestoneId;

    @Nullable
    @Schema(example = "Milestone AB", description = "Matches an existing milestone by (projectId, milestoneTitle), or names a new one.")
    private String milestoneTitle;

    @Nullable
    @Schema(example = "Q3-2-1", description = "Permanent identifier assigned when the milestone was created (see MilestoneView#proId). "
            + "When supplied, matches the existing milestone by it directly — the reliable way to reference one that may have since been "
            + "renamed. When omitted, falls back to matching by the current milestoneTitle. Only meaningful for matching an existing "
            + "milestone — a milestone's proId is always system-assigned on creation and cannot be chosen, so this is ignored if no "
            + "existing milestone matches and a new one is created instead.")
    private String proId;

    @Nullable
    @Schema(example = "Site survey and vendor contract signature")
    private String description;

    @Nullable
    @Schema(example = "50000.00")
    private BigDecimal milestoneAmount;

    @Nullable
    @Schema(example = "USD")
    private String currency;

    @Nullable
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(example = "2025-06-30")
    private LocalDate milestoneDate;

    @Nullable
    @Schema(example = "DELETE", description = "When set to \"DELETE\", this milestone (matched by proId, "
            + "falling back to milestoneTitle, same as for an update) is deleted instead of created/updated. "
            + "All other fields on this node are ignored. Omit, or leave null, for the normal "
            + "create-or-update behavior.")
    private String action;

}
