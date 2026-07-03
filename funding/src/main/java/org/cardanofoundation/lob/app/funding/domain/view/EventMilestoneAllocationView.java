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
public class EventMilestoneAllocationView {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440001")
    private String eventId;

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000",
            description = "Internal UUID of the milestone (milestone_id)")
    private String milestoneId;

    @Nullable
    @Schema(example = "MS-001", description = "User-defined Milestone ID")
    private String externalMilestoneId;

    @Schema(example = "Milestone AB")
    private String milestoneTitle;

    @Schema(example = "50000.00")
    private BigDecimal milestoneAmount;

    @Nullable
    @Schema(example = "50000.00")
    private BigDecimal allocatedAmount;

    @Schema(example = "USD")
    private String currency;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(example = "2025-06-30")
    private LocalDate milestoneDate;

    // --- Spend detail: SPENDING events only ---

    @Nullable
    @Schema(example = "Personnel")
    private String category;

    @Nullable
    @Schema(example = "Vendor AB")
    private String vendor;

    @Nullable
    @Schema(example = "100000.00")
    private BigDecimal amountFcy;

    @Nullable
    @Schema(example = "EUR", description = "Foreign currency of the spend.")
    private String spendCurrency;

    @Nullable
    @Schema(example = "1.176470")
    private BigDecimal fxRate;

    @Nullable
    @Schema(example = "85000.00")
    private BigDecimal amountRcy;

    @Nullable
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
