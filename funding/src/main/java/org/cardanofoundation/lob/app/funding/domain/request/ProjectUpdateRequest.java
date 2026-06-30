package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;

import lombok.*;
import lombok.experimental.SuperBuilder;

import io.swagger.v3.oas.annotations.media.Schema;


@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class ProjectUpdateRequest {

    @Nullable
    @Schema(example = "Updated Project Name")
    private String projectTitle;

    @Nullable
    @Schema(example = "250000.00")
    private BigDecimal totalAmount;

    @Nullable
    @NotBlank
    @Schema(example = "EUR")
    private String currency;

}
