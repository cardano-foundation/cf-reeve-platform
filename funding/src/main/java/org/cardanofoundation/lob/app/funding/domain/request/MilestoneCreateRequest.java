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
    @Schema(example = "existing-milestone-id", description = "If provided, reference an existing milestone instead of creating a new one")
    private String milestoneId;

    @Nullable
    @Schema(example = "Milestone AB")
    private String label;

    @Nullable
    @Schema(example = "50000.00")
    private BigDecimal expectedCost;

    @Nullable
    @Schema(example = "USD")
    private String currency;

    @Nullable
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(example = "2025-06-30")
    private LocalDate dueDate;

}
