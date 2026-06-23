package org.cardanofoundation.lob.app.funding.domain.view;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.*;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Builder
@AllArgsConstructor
public class MilestoneView {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
    private String milestoneId;

    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa")
    private String projectId;

    @Schema(example = "Milestone AB")
    private String label;

    @Schema(example = "50000.00")
    private BigDecimal expectedCost;

    @Schema(example = "USD")
    private String currency;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(example = "2025-06-30")
    private LocalDate dueDate;

}
