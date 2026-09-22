package org.cardanofoundation.lob.app.funding.domain.view;

import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.springframework.http.ProblemDetail;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Whether a project has at least one linked event still in Draft status — lets the edit flow decide
 * whether to show the draft warning before opening the edit form (LOB-2365). Checked across the whole
 * subtree (the project itself and every descendant sub-project), mirroring the scope
 * {@code ProjectService#updateProject}'s own published-event lock check already uses.
 */
@Getter
@Builder
@AllArgsConstructor
public class ProjectDraftStatusView implements ErrorAware {

    @Schema(description = "True when at least one Draft-status event allocates anywhere in this project's structure.")
    private boolean hasDraftEvent;

    @Builder.Default
    @Schema(description = "Problem detail describing the failure; absent on success")
    private Optional<ProblemDetail> error = Optional.empty();

    /** A failure response carrying only the problem detail. */
    public static ProjectDraftStatusView error(ProblemDetail error) {
        return ProjectDraftStatusView.builder().error(Optional.of(error)).build();
    }

}
