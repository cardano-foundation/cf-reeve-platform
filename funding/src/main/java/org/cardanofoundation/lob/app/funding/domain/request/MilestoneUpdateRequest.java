package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;

import lombok.*;
import lombok.experimental.SuperBuilder;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class MilestoneUpdateRequest {

    @Nullable
    @Schema(example = "Updated Milestone Name")
    private String milestoneTitle;

    @Nullable
    @Schema(example = "75000.00")
    private BigDecimal milestoneAmount;

    @Nullable
    @NotBlank
    @Schema(example = "USD")
    private String currency;

    @Nullable
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(example = "2025-09-30")
    private LocalDate milestoneDate;

}
