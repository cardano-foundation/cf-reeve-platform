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

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
    private String milestoneId;

    @Schema(example = "Milestone AB")
    private String milestoneLabel;

    @Schema(example = "50000.00")
    private BigDecimal expectedCost;

    @Nullable
    @Schema(example = "50000.00")
    private BigDecimal allocatedAmount;

    @Schema(example = "USD")
    private String currency;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(example = "2025-06-30")
    private LocalDate dueDate;

}
