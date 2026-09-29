package org.cardanofoundation.lob.app.funding.domain.view;

import java.math.BigDecimal;
import java.util.List;

import lombok.*;

import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Builder
@AllArgsConstructor
public class EventProjectAllocationView {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440001")
    private String eventId;

    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa",
            description = "Internal SHA256 id of the project (project_id)")
    private String projectId;

    @Schema(example = "PROJ-AB", description = "User-defined project identifier")
    private String externalProjectId;

    @Schema(example = "Project AB")
    private String projectTitle;

    /** Set when this allocation targets a sub-project; contains the root project id (SHA256). */
    @jakarta.annotation.Nullable
    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa")
    private String parentProjectId;

    private List<EventMilestoneAllocationView> milestoneAllocations;

    @jakarta.annotation.Nullable
    @Schema(example = "200000.00", description = "This (leaf) project's own budget, when set.")
    private BigDecimal totalAmount;

    @Schema(example = "215000.00",
            description = "Cumulative SPENDING allocated directly to this project's milestones, including this event.")
    private BigDecimal spentAmount;

    @Schema(example = "true",
            description = "True when spentAmount exceeds totalAmount — a warning condition, not a rejection.")
    private boolean overspend;

}
