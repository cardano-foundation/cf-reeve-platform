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

    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa")
    private String projectId;

    @Schema(example = "PROJ-AB")
    private String activityId;

    @Schema(example = "Project AB")
    private String activityTitle;

    @Schema(example = "WP-1")
    private String activitySubId;

    private List<EventMilestoneAllocationView> milestoneAllocations;

}
