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

    @Nullable
    @Schema(example = "9a1b2c3d4e5f6789",
            description = "Internal id of the project to attach this project under as a sub-project. "
                    + "Must belong to the same organisation and must not create a circular dependency.")
    private String parentProjectId;

}
