package org.cardanofoundation.lob.app.funding.domain.view;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.annotation.Nullable;

import lombok.*;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Builder
@AllArgsConstructor
public class FundingItemView {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
    private String itemId;

    @Schema(example = "550e8400-e29b-41d4-a716-446655440001")
    private String eventId;

    @Schema(example = "Personnel")
    private String category;

    @Schema(example = "Vendor AB")
    private String vendor;

    @Schema(example = "100000.00")
    private BigDecimal amountFcy;

    @Schema(example = "USD")
    private String currency;

    @Nullable
    @Schema(example = "0.85")
    private BigDecimal fxRate;

    @Nullable
    @Schema(example = "85000.00")
    private BigDecimal amountRcy;

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
