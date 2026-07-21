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
    @Schema(example = "MS-1", description = "User-defined milestone ID. Used to reference an existing milestone by (projectId, externalMilestoneId) or to name a new milestone.")
    private String externalMilestoneId;

    @Nullable
    @Schema(example = "Milestone AB")
    private String milestoneTitle;

    @Nullable
    @Schema(example = "50000.00")
    private BigDecimal milestoneAmount;

    @Nullable
    @Schema(example = "USD")
    private String currency;

    @Nullable
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(example = "2025-06-30")
    private LocalDate milestoneDate;

}
