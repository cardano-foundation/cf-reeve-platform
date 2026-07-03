package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import lombok.*;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class EventMilestoneAllocationRequest {

    /** Milestone details — supply milestoneId to reference an existing one, or fill the other fields to create a new one. */
    @NotNull
    @Valid
    private MilestoneCreateRequest milestone;

    /** Amount allocated to this milestone. Required for FUNDING and REFUND events. */
    @Nullable
    @Schema(example = "50000.00")
    private BigDecimal allocatedAmount;

    // --- Spend detail: SPENDING events only (omitted for FUNDING/REFUND) ---

    @Nullable
    @Schema(example = "Personnel", description = "SPENDING events only.")
    private String category;

    @Nullable
    @Schema(example = "Vendor AB", description = "SPENDING events only.")
    private String vendor;

    @Nullable
    @Schema(example = "100000.00", description = "Foreign-currency amount spent. SPENDING events only.")
    private BigDecimal amountFcy;

    @Nullable
    @Schema(example = "EUR", description = "Foreign currency of the spend. SPENDING events only.")
    private String currency;

    @Nullable
    @Schema(example = "1.176470", description = "FX rate such that amountFcy = amountRcy * fxRate. SPENDING events only.")
    private BigDecimal fxRate;

    @Nullable
    @Schema(example = "85000.00", description = "Reporting-currency amount. SPENDING events only.")
    private BigDecimal amountRcy;

    @Nullable
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(example = "2025-04-03", description = "SPENDING events only.")
    private LocalDate spendDate;

    @Nullable
    @Schema(example = "sha256:abc123...", description = "SPENDING events only.")
    private String hash;

    @Nullable
    @Schema(example = "Invoice #INV-2025-001", description = "SPENDING events only.")
    private String notes;

}
