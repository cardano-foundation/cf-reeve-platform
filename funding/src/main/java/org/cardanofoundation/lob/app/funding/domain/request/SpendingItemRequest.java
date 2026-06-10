package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.annotation.Nullable;
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
public class SpendingItemRequest {

    @NotBlank
    @Schema(example = "Personnel")
    private String category;

    @NotBlank
    @Schema(example = "Vendor AB")
    private String vendor;

    @NotNull
    @Schema(example = "100000.00")
    private BigDecimal amountFcy;

    @NotBlank
    @Schema(example = "USD")
    private String currency;

    @Nullable
    @Schema(example = "0.85")
    private BigDecimal fxRate;

    @Nullable
    @Schema(example = "85000.00")
    private BigDecimal amountRcy;

    @NotNull
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(example = "2025-04-03")
    private LocalDate spendDate;

    @Nullable
    @Schema(example = "sha256:abc123...")
    private String hash;

    @Nullable
    @Schema(example = "Invoice #INV-2025-001")
    private String notes;

}
