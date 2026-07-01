package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;

import lombok.*;

import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class EventProjectAllocationRequest {

    // --- Existing project: supply externalProjectId (user-defined) only ---

    @Nullable
    @Schema(example = "PROJ-AB",
            description = "User-defined project ID (externalProjectId field). When externalProjectId maps to an existing project, all new-project fields below are ignored.")
    private String externalProjectId;

    // --- New project: supply the fields below when creating a new project ---

    @Nullable
    @Schema(example = "GRANT-2025-001")
    private String fundingId;

    @Nullable
    @Schema(example = "Project AB")
    private String projectTitle;

    @Nullable
    @Schema(example = "200000.00", description = "Required when creating a new root project without a sub-project.")
    private BigDecimal totalAmount;

    @Nullable
    @Schema(example = "USD", description = "Required when creating a new root project without a sub-project.")
    private String currency;

    /**
     * Optional sub-project under the resolved/created root project.
     * When set, milestones are attached to the sub-project and the allocation targets the sub-project.
     * When null, the allocation targets the root project directly.
     */
    @Nullable
    private SubProjectRequest subProject;

    /**
     * Milestones to allocate to within this project. Optional — an allocation may carry no milestones
     * (e.g. a SPENDING event whose amount comes from line items). FUNDING/REFUND events must still
     * allocate a positive amount overall (see the event-total validation).
     */
    @Builder.Default
    @Valid
    private List<EventMilestoneAllocationRequest> milestones = new ArrayList<>();

}
