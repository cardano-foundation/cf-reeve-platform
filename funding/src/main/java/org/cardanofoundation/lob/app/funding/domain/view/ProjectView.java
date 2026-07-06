package org.cardanofoundation.lob.app.funding.domain.view;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.annotation.Nullable;

import lombok.*;

import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Builder
@AllArgsConstructor
public class ProjectView implements ErrorAware {

    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa",
            description = "Internal SHA256 unique identifier (project_id)")
    private String projectId;

    @Schema(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
    private String organisationId;

    @Schema(example = "GRANT-2025-001")
    private String fundingId;

    @Schema(example = "PROJ-AB", description = "User-defined project identifier")
    private String externalProjectId;

    @Schema(example = "Project AB")
    private String projectTitle;

    @Nullable
    @Schema(example = "200000.00", description = "Null for sub-projects.")
    private BigDecimal totalAmount;

    @Nullable
    @Schema(example = "USD", description = "Null for sub-projects.")
    private String currency;

    /** Calculated (not stored): total spent across this project's milestones and sub-projects. */
    @Nullable
    @Schema(example = "12000.00", description = "Spent = allocated SPENDING amounts minus REFUND amounts.")
    private BigDecimal spentAmount;

    /** Null for root projects; set for sub-projects (SHA256 id of the parent). */
    @Nullable
    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa")
    private String parentProjectId;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(example = "2025-01-10T00:00:00")
    private LocalDateTime createdAt;

    private List<MilestoneView> milestones;

    /** Sub-projects; empty for leaf nodes. */
    private List<ProjectView> subProjects;

    /** Events (FUNDING/SPENDING/REFUND) allocated to this project. Populated on get-by-id only. */
    @Nullable
    private List<SpendingEventView> events;

    @Builder.Default
    @Schema(description = "Problem detail describing the failure; absent on success")
    private Optional<ProblemDetail> error = Optional.empty();

    /** A failure response carrying only the problem detail. */
    public static ProjectView error(ProblemDetail error) {
        return ProjectView.builder().error(Optional.of(error)).build();
    }

}
