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
            description = "User-defined project ID. No longer used to match an existing project — projects are matched by projectTitle. Accepted and stored for backward compatibility only.")
    private String externalProjectId;

    // --- New project: supply the fields below when creating a new project ---

    @Nullable
    @Schema(example = "GRANT-2025-001-AB", description = "Optional funding reference of the project. Unique per organisation — no two projects may share it.")
    private String fundingId;

    @Nullable
    @Schema(example = "Project AB", description = "Required. Matches an existing root project by (organisationId, projectTitle), or names a new one.")
    private String projectTitle;

    @Nullable
    @Schema(example = "200000.00", description = "Required when creating a new root project without a sub-project.")
    private BigDecimal totalAmount;

    @Nullable
    @Schema(example = "USD", description = "Required when creating a new root project without a sub-project.")
    private String currency;

    /**
     * Milestone allocations against this (root) project — mutually exclusive with {@code subProjects}.
     * Optional — an allocation may instead push everything into sub-projects.
     */
    @Builder.Default
    @Valid
    private List<EventMilestoneAllocationRequest> milestones = new ArrayList<>();

    /**
     * Sub-project tree under this root — the same recursive shape as the create-project endpoint,
     * with each milestone carrying an {@code allocatedAmount}. Mutually exclusive with {@code milestones}.
     */
    @Builder.Default
    @Valid
    private List<EventSubProjectAllocationRequest> subProjects = new ArrayList<>();

}
