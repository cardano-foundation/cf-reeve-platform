package org.cardanofoundation.lob.app.funding.domain.view;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import jakarta.annotation.Nullable;

import lombok.*;

import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Builder
@AllArgsConstructor
public class MilestoneView implements ErrorAware {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000",
            description = "Internal UUID unique identifier (milestone_id)")
    private String milestoneId;

    @Nullable
    @Schema(example = "MS-1", description = "User-defined milestone identifier")
    private String externalMilestoneId;

    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa",
            description = "Internal SHA256 id of the parent project (project_id)")
    private String projectId;

    @Schema(example = "Milestone AB")
    private String milestoneTitle;

    @Schema(example = "50000.00")
    private BigDecimal milestoneAmount;

    @Schema(example = "USD")
    private String currency;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(example = "2025-06-30")
    private LocalDate milestoneDate;

    /** Calculated (not stored): sum of SPENDING allocations for this milestone (FUNDING and REFUND excluded). */
    @Nullable
    @Schema(example = "12000.00", description = "Spent = sum of allocated SPENDING amounts. FUNDING and REFUND do not affect this total.")
    private BigDecimal spentAmount;

    @Builder.Default
    @Schema(description = "Problem detail describing the failure; absent on success")
    private Optional<ProblemDetail> error = Optional.empty();

    /** A failure response carrying only the problem detail. */
    public static MilestoneView error(ProblemDetail error) {
        return MilestoneView.builder().error(Optional.of(error)).build();
    }

}
