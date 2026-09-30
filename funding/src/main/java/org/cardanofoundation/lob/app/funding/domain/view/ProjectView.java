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

import org.cardanofoundation.lob.app.funding.domain.enums.ProjectLockStatus;

@Getter
@Builder(toBuilder = true)
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

    @Schema(example = "Project AB", description = "Permanent identifier assigned at creation — never changes afterward, even when projectTitle is later renamed. "
            + "Root project: the value supplied at creation, or the title as first typed when none was. Sub-project: '<parent proId>-S<n>' "
            + "when created via the API or an event allocation, or the value supplied when created via CSV (sub-projects that predate "
            + "this field keep their original title). Reference this, not projectTitle, when the project "
            + "needs to be found reliably later (e.g. a subsequent event allocation or CSV re-upload).")
    private String proId;

    @Nullable
    @Schema(example = "200000.00", description = "Null for sub-projects.")
    private BigDecimal totalAmount;

    @Nullable
    @Schema(example = "USD", description = "Null for sub-projects.")
    private String currency;

    /** Calculated (not stored): total spent across this project's milestones and sub-projects. */
    @Nullable
    @Schema(example = "12000.00", description = "Spent = sum of allocated SPENDING amounts. FUNDING and REFUND do not affect this total.")
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

    /**
     * Calculated (not stored): aggregate structural lock status for this project's own subtree (its
     * milestones and, recursively, its sub-projects) — see {@link ProjectLockStatus}. LOB-2365.
     */
    @Schema(description = "EDITABLE (no published events anywhere in this project's structure), "
            + "PARTLY_LOCKED (at least one published event exists somewhere, but at least one milestone/sub-project remains unallocated), "
            + "or LOCKED (every milestone/structural component is tied to a published event).")
    private ProjectLockStatus lockStatus;

    /** Events (FUNDING/SPENDING/REFUND) allocated to this project. Populated on get-by-id only. */
    @Nullable
    private List<SpendingEventView> events;

    @Builder.Default
    @Schema(description = "Non-published events that had an allocation removed by a DELETE node in this same "
            + "update and were flagged ERROR as a result (LOB-2365 follow-up) — see CascadeDeletionView. "
            + "Empty when this update deleted nothing, or deleted nothing that affected an event.")
    private List<AffectedEventView> affectedEvents = List.of();

    @Builder.Default
    @Schema(description = "Problem detail describing the failure; absent on success")
    private Optional<ProblemDetail> error = Optional.empty();

    /** A failure response carrying only the problem detail. */
    public static ProjectView error(ProblemDetail error) {
        return ProjectView.builder().error(Optional.of(error)).build();
    }

}
