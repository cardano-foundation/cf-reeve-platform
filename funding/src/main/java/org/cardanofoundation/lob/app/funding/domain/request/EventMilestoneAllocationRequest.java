package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;

import lombok.*;

import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class EventMilestoneAllocationRequest {

    @NotBlank
    @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
    private String milestoneId;

    @Nullable
    @Schema(example = "50000.00")
    private BigDecimal allocatedAmount;

}
