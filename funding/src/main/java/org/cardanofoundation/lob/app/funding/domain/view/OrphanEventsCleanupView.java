package org.cardanofoundation.lob.app.funding.domain.view;

import java.util.List;
import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.springframework.http.ProblemDetail;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response for the bulk orphaned-event cleanup endpoint (LOB-2365 follow-up). {@code deletedEvents}
 * lists every event that was actually deleted — an {@code ERROR} event, for this organisation, with no
 * milestone allocations left at all (every one was removed by an earlier project/milestone cascade
 * delete, so there is nothing left to reconcile). An {@code ERROR} event that still has at least one
 * real allocation left is never touched by this endpoint — that one still needs a human to review it
 * via the normal event edit/delete flow instead, since it may still hold data worth fixing rather than
 * discarding.
 */
@Getter
@Builder
@AllArgsConstructor
public class OrphanEventsCleanupView implements ErrorAware {

    @Builder.Default
    @Schema(description = "Events that were deleted by this cleanup — ERROR events none of whose allocations points at an existing milestone.")
    private List<AffectedEventView> deletedEvents = List.of();

    @Builder.Default
    @Schema(description = "Problem detail describing the failure; absent on success")
    private Optional<ProblemDetail> error = Optional.empty();

    public static OrphanEventsCleanupView success(List<AffectedEventView> deletedEvents) {
        return OrphanEventsCleanupView.builder().deletedEvents(deletedEvents).build();
    }

    public static OrphanEventsCleanupView error(ProblemDetail error) {
        return OrphanEventsCleanupView.builder().error(Optional.of(error)).build();
    }

}
