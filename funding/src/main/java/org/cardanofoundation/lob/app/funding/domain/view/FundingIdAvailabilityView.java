package org.cardanofoundation.lob.app.funding.domain.view;

import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.springframework.http.ProblemDetail;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response for the real-time Funding ID uniqueness check the UI calls on manual entry. */
@Getter
@Builder
@AllArgsConstructor
public class FundingIdAvailabilityView implements ErrorAware {

    @Schema(description = "True when fundingId is not already used by another FUNDING event in this organisation",
            example = "true")
    private boolean available;

    @Builder.Default
    @Schema(description = "Problem detail describing the failure; absent on success")
    private Optional<ProblemDetail> error = Optional.empty();

    /** A failure response carrying only the problem detail. */
    public static FundingIdAvailabilityView error(ProblemDetail error) {
        return FundingIdAvailabilityView.builder().error(Optional.of(error)).build();
    }

}
