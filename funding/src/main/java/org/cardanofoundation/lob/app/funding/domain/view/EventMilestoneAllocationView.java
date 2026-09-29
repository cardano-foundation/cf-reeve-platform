package org.cardanofoundation.lob.app.funding.domain.view;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.annotation.Nullable;

import lombok.*;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Builder
@AllArgsConstructor
public class EventMilestoneAllocationView {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440001")
    private String eventId;

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000",
            description = "Internal UUID of the milestone (milestone_id)")
    private String milestoneId;

    @Nullable
    @Schema(example = "MS-001", description = "User-defined Milestone ID")
    private String externalMilestoneId;

    @Schema(example = "Milestone AB")
    private String milestoneTitle;

    @Schema(example = "50000.00")
    private BigDecimal milestoneAmount;

    @Nullable
    @Schema(example = "50000.00")
    private BigDecimal allocatedAmount;

    @Schema(example = "USD")
    private String currency;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(example = "2025-06-30")
    private LocalDate milestoneDate;

    @Schema(example = "55000.00",
            description = "Cumulative SPENDING allocated to this milestone across all events, including this one.")
    private BigDecimal spentAmount;

    @Schema(example = "true",
            description = "True when spentAmount exceeds milestoneAmount — a warning condition, not a rejection.")
    private boolean overspend;

}
