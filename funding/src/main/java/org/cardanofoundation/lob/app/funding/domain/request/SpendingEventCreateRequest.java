package org.cardanofoundation.lob.app.funding.domain.request;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.*;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SpendingEventCreateRequest {

    @NotNull
    @Schema(example = "SPENDING")
    private EventType eventType;

    @NotBlank
    @Schema(example = "GRANT-2025-001")
    private String fundingId;

    @NotBlank
    @Schema(example = "PROJ-AB")
    private String activityId;

    @NotBlank
    @Schema(example = "USD")
    private String currency;

    /** Required for SPENDING events — links batch to a single milestone. */
    @Nullable
    @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
    private String milestoneId;

    /** Optional — disbursement tx hash (used when Event 1 is omitted). */
    @Nullable
    @Schema(example = "2736ff28...")
    private String fundingTx;

    /** Spend line items — used only for SPENDING events. */
    @Builder.Default
    @Valid
    private List<SpendingItemRequest> spendingItems = new ArrayList<>();

    /** Milestone allocations — used only for FUNDING and REFUND events. */
    @Builder.Default
    @Valid
    private List<EventMilestoneAllocationRequest> milestoneAllocations = new ArrayList<>();

}
