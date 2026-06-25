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

    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa",
            description = "Internal SHA256 uid of the project (project_uid)")
    private String projectUid;

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000",
            description = "Internal UUID of the milestone (milestone_uid)")
    private String milestoneUid;

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

}
