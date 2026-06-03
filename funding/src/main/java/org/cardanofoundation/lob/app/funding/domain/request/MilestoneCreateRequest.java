package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.*;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MilestoneCreateRequest {

    @NotBlank
    @Schema(example = "Milestone AB")
    private String label;

    @NotNull
    @Schema(example = "50000.00")
    private BigDecimal expectedCost;

    @NotBlank
    @Schema(example = "USD")
    private String currency;

    @NotNull
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(example = "2025-06-30")
    private LocalDate dueDate;

}
