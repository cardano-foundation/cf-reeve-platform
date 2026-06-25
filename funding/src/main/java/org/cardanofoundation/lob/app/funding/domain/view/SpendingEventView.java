package org.cardanofoundation.lob.app.funding.domain.view;

import java.math.BigDecimal;
import java.util.List;

import jakarta.annotation.Nullable;

import lombok.*;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

@Getter
@Builder
@AllArgsConstructor
public class SpendingEventView {

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
    private String fundingTx;

    /** One entry per project this event is allocated to. */
    private List<EventProjectAllocationView> projectAllocations;

    /** Spend line items (SPENDING events only). */
    private List<SpendingItemView> spendingItems;

}
