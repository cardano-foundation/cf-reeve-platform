package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import lombok.*;

import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class EventProjectAllocationRequest {

    // --- Existing project: supply projectId only ---

    @Nullable
    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa",
            description = "ID of an existing project. When set, all new-project fields below are ignored.")
    private String projectId;

    // --- New project: supply the fields below when projectId is null ---

    @Nullable
    @Schema(example = "GRANT-2025-001")
    private String fundingId;

    @Nullable
    @Schema(example = "PROJ-AB")
    private String activityId;

    @Nullable
    @Schema(example = "Project AB")
    private String activityTitle;

    @Nullable
    @Schema(example = "WP-1", description = "Optional sub-project / workstream identifier")
    private String activitySubId;

    @Nullable
    @Schema(example = "200000.00")
    private BigDecimal expectedTotalAmount;

    @Nullable
    @Schema(example = "USD")
    private String currency;

    /** At least one milestone must be supplied per project allocation. */
    @NotEmpty
    @Builder.Default
    @Valid
    private List<EventMilestoneAllocationRequest> milestones = new ArrayList<>();

}
