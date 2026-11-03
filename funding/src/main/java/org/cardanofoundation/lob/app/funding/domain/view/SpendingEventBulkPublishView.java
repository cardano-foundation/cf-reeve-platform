package org.cardanofoundation.lob.app.funding.domain.view;

import java.util.List;
import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.springframework.http.ProblemDetail;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response for the bulk publish endpoint (LOB-2391): one {@link Result} per distinct requested event
 * id, in request order. A skipped event carries the same problem detail single-event publish would
 * have returned for it. The top-level {@code error} is only set when the whole request is rejected
 * (no access to the organisation, organisation not found).
 */
@Getter
@Builder
@AllArgsConstructor
public class SpendingEventBulkPublishView implements ErrorAware {

    public enum Outcome { PUBLISHED, SKIPPED }

    @Builder.Default
    @Schema(description = "Outcome per distinct requested event id, in request order.")
    private List<Result> results = List.of();

    @Builder.Default
    @Schema(description = "Problem detail describing why the whole request was rejected; absent on success")
    private Optional<ProblemDetail> error = Optional.empty();

    public static SpendingEventBulkPublishView success(List<Result> results) {
        return SpendingEventBulkPublishView.builder().results(results).build();
    }

    public static SpendingEventBulkPublishView error(ProblemDetail error) {
        return SpendingEventBulkPublishView.builder().error(Optional.of(error)).build();
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Result {

        @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa")
        private String eventId;

        @Schema(example = "GRANT-2025-001", description = "User-facing funding reference; absent when the event was not found.")
        private String fundingId;

        private Outcome outcome;

        @Builder.Default
        @Schema(description = "Why the event was skipped — the same problem single-event publish returns; absent when published.")
        private Optional<ProblemDetail> error = Optional.empty();

        public static Result published(String eventId, String fundingId) {
            return Result.builder().eventId(eventId).fundingId(fundingId).outcome(Outcome.PUBLISHED).build();
        }

        public static Result skipped(String eventId, String fundingId, ProblemDetail problem) {
            return Result.builder().eventId(eventId).fundingId(fundingId).outcome(Outcome.SKIPPED)
                    .error(Optional.of(problem)).build();
        }
    }

}
