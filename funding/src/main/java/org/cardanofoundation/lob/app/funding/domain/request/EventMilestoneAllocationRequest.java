package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import lombok.*;

import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class EventMilestoneAllocationRequest {

    /** Milestone details — supply milestoneId to reference an existing one, or fill the other fields to create a new one. */
    @NotNull
    @Valid
    private MilestoneCreateRequest milestone;

    /** Amount allocated to this milestone (required for FUNDING and REFUND events). */
    @Nullable
    @Schema(example = "50000.00")
    private BigDecimal allocatedAmount;

}
