package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import lombok.*;
import lombok.experimental.SuperBuilder;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

/**
 * Request body for creating (or updating) a funding event together with its project-milestone
 * allocations in a single call. Project and milestone references can be either existing IDs or
 * inline creation payloads — the service resolves or creates them automatically.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class SpendingEventCreateRequest extends BaseRequest {

    @NotNull
    @Schema(example = "SPENDING")
    private EventType eventType;

    @NotBlank
    @Schema(example = "GRANT-2025-001")
    private String fundingId;

    @Nullable
    @Schema(example = "2736ff28abc...")
    private String fundingHash;

    @Nullable
    @Schema(example = "Cardano Foundation", description = "Name of the entity providing the funding. Required for FUNDING events.")
    private String fundingEntity;

    @NotBlank
    @Schema(example = "USD", description = "Reporting currency of the event.")
    private String currencyRcy;

    @Nullable
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(example = "2025-04-03", description = "Event date. Applies to all event types (FUNDING, SPENDING, REFUND). For a SPENDING event this is the spend date.")
    private LocalDate eventDate;

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
    private String currencyFcy;

    @Nullable
    @Schema(example = "1.176470", description = "FX rate such that amountFcy = amountRcy * fxRate. SPENDING events only.")
    private BigDecimal fxRate;

    @Nullable
    @Schema(example = "85000.00", description = "Reporting-currency amount spent. SPENDING events only.")
    private BigDecimal amountRcy;

    @Nullable
    @Schema(example = "sha256:abc123...", description = "SPENDING events only.")
    private String hash;

    @Nullable
    @Schema(example = "Invoice #INV-2025-001", description = "SPENDING events only.")
    private String notes;

    /**
     * Project allocations for this event. Each entry links the event to one project and specifies
     * which milestones of that project are targeted. At least one allocation is required.
     */
    @NotEmpty
    @Builder.Default
    @Valid
    private List<EventProjectAllocationRequest> allocations = new ArrayList<>();

}
