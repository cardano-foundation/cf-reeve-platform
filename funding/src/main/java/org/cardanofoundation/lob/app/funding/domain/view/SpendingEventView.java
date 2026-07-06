package org.cardanofoundation.lob.app.funding.domain.view;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import jakarta.annotation.Nullable;

import lombok.*;

import org.springframework.http.ProblemDetail;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

@Getter
@Builder
@AllArgsConstructor
public class SpendingEventView implements ErrorAware {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
    private String eventId;

    @Schema(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
    private String organisationId;

    @Schema(example = "SPENDING")
    private EventType eventType;

    @Schema(example = "DRAFT")
    private EventStatus status;

    @Schema(example = "GRANT-2025-001")
    private String fundingId;

    @Schema(example = "230000000.00")
    private BigDecimal totalAmount;

    @Schema(example = "USD")
    private String currency;

    @Nullable
    @Schema(example = "2736...FF28")
    private String txHash;

    @Nullable
    @Schema(example = "FINALIZED")
    private LedgerDispatchStatus ledgerDispatchStatus;

    @Nullable
    @Schema(example = "2736ff28...")
    private String fundingHash;

    @Nullable
    @Schema(example = "Cardano Foundation")
    private String fundingEntity;

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
    @Schema(example = "2025-04-03")
    private java.time.LocalDate spendDate;

    @Nullable
    @Schema(example = "sha256:abc123...")
    private String hash;

    @Nullable
    @Schema(example = "Invoice #INV-2025-001")
    private String notes;

    /** One entry per project this event is allocated to. */
    private List<EventProjectAllocationView> projectAllocations;

    @Builder.Default
    @Schema(description = "Problem detail describing the failure; absent on success")
    private Optional<ProblemDetail> error = Optional.empty();

    /** A failure response carrying only the problem detail. */
    public static SpendingEventView error(ProblemDetail error) {
        return SpendingEventView.builder().error(Optional.of(error)).build();
    }

}
