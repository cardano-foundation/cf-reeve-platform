package org.cardanofoundation.lob.app.funding.domain.view;

import java.math.BigDecimal;

import jakarta.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An allocation of an event whose milestone no longer exists (it, or a project above it, was deleted —
 * LOB-2365 follow-up). The allocation row itself is never removed by that delete; it stays as a dangling
 * reference for a human to resolve, so the UI can warn about it. Nothing about the deleted milestone
 * (title, project, amount) is kept — only what the allocation row itself carries.
 */
@Getter
@Builder
@AllArgsConstructor
public class OrphanedAllocationView {

    @Schema(description = "Internal id the allocation still points at — no milestone with this id exists any more.")
    private String milestoneId;

    @Nullable
    @Schema(example = "10000.00", description = "The amount this event had allocated to the deleted milestone.")
    private BigDecimal allocatedAmount;

    @Schema(example = "true", description = "Always true here: the milestone this allocation points at was deleted.")
    private boolean milestoneDeleted;

}
