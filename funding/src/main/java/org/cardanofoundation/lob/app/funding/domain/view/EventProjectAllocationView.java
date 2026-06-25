package org.cardanofoundation.lob.app.funding.domain.view;

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
            description = "Internal SHA256 uid of the project (project_uid)")
    private String projectUid;

    @Schema(example = "PROJ-AB", description = "User-defined project identifier")
    private String projectId;

    @Schema(example = "Project AB")
    private String projectTitle;

    /** Set when this allocation targets a sub-project; contains the root project UID (SHA256). */
    @jakarta.annotation.Nullable
    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa")
    private String parentProjectUid;

    private List<EventMilestoneAllocationView> milestoneAllocations;

}
