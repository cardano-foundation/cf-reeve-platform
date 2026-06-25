package org.cardanofoundation.lob.app.funding.domain.request;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import lombok.*;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

/**
 * Request body for creating (or updating) a funding event together with its project-milestone
 * allocations in a single call. Project and milestone references can be either existing IDs or
 * inline creation payloads — the service resolves or creates them automatically.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SpendingEventCreateRequest {

    @NotBlank
    @Schema(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
    private String organisationId;

    @NotNull
    @Schema(example = "SPENDING")
    private EventType eventType;

    @NotBlank
    @Schema(example = "GRANT-2025-001")
    private String fundingId;

    @Nullable
    @Schema(example = "2736ff28abc...")
    private String fundingTx;

    @NotBlank
    @Schema(example = "USD")
    private String currency;

    /**
     * Project allocations for this event. Each entry links the event to one project and specifies
     * which milestones of that project are targeted. At least one allocation is required.
     */
    @NotEmpty
    @Builder.Default
    @Valid
    private List<EventProjectAllocationRequest> allocations = new ArrayList<>();

    /** Spend line items — required for SPENDING events, ignored for FUNDING and REFUND. */
    @Builder.Default
    @Valid
    private List<SpendingItemRequest> items = new ArrayList<>();

}
