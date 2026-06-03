package org.cardanofoundation.lob.app.funding.domain.view;

import java.math.BigDecimal;
import java.util.List;

import jakarta.annotation.Nullable;

import lombok.*;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

@Getter
@Builder
@AllArgsConstructor
public class SpendingEventView {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
    private String eventId;

    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa")
    private String projectId;

    @Schema(example = "SPENDING")
    private EventType eventType;

    @Schema(example = "DRAFT")
    private EventStatus status;

    @Schema(example = "GRANT-2025-001")
    private String fundingId;

    @Schema(example = "PROJ-AB")
    private String activityId;

    @Schema(example = "230000000.00")
    private BigDecimal totalAmount;

    @Schema(example = "USD")
    private String currency;

    @Nullable
    @Schema(example = "2736...FF28")
    private String txHash;

    @Nullable
    @Schema(example = "2736ff28...")
    private String fundingTx;

    @Nullable
    @Schema(example = "550e8400-e29b-41d4-a716-446655440001")
    private String milestoneId;

    @Nullable
    @Schema(example = "Milestone AB")
    private String milestoneLabel;

    private List<SpendingItemView> spendingItems;

    private List<EventMilestoneAllocationView> milestoneAllocations;

}
