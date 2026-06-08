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

    @NotNull
    @Valid
    private MilestoneCreateRequest milestone;

    @Nullable
    @Schema(example = "50000.00")
    private BigDecimal allocatedAmount;

}
