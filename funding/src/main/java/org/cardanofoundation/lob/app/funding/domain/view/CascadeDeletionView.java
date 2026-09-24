package org.cardanofoundation.lob.app.funding.domain.view;

import java.util.List;
import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.springframework.http.ProblemDetail;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response for deleting a project or milestone (LOB-2365 follow-up). On success, {@code affectedEvents}
 * lists every non-published event that had an allocation removed as part of this delete and was flagged
 * {@code ERROR} as a result — the event itself is never deleted, so a human still needs to review and
 * either fix (edit) or delete each one (see {@code DELETE /events/{eventId}}, and the bulk
 * {@code DELETE /events/orphans} cleanup endpoint for the fully-unallocated case). Empty when the delete
 * didn't touch any event.
 */
@Getter
@Builder
@AllArgsConstructor
public class CascadeDeletionView implements ErrorAware {

    @Builder.Default
    @Schema(description = "Non-published events that had an allocation into the deleted scope removed and were "
            + "flagged ERROR as a result. Empty when nothing was affected.")
    private List<AffectedEventView> affectedEvents = List.of();

    @Builder.Default
    @Schema(description = "Problem detail describing the failure; absent on success")
    private Optional<ProblemDetail> error = Optional.empty();

    public static CascadeDeletionView success(List<AffectedEventView> affectedEvents) {
        return CascadeDeletionView.builder().affectedEvents(affectedEvents).build();
    }

    public static CascadeDeletionView error(ProblemDetail error) {
        return CascadeDeletionView.builder().error(Optional.of(error)).build();
    }

}
