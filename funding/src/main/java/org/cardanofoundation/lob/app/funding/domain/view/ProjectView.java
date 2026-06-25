package org.cardanofoundation.lob.app.funding.domain.view;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.annotation.Nullable;

import lombok.*;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Builder
@AllArgsConstructor
public class ProjectView {

    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa",
            description = "Internal SHA256 unique identifier (project_uid)")
    private String projectUid;

    @Schema(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
    private String organisationId;

    @Schema(example = "GRANT-2025-001")
    private String fundingId;

    @Schema(example = "PROJ-AB", description = "User-defined project identifier")
    private String projectId;

    @Schema(example = "Project AB")
    private String projectTitle;

    @Nullable
    @Schema(example = "200000.00", description = "Null for sub-projects.")
    private BigDecimal totalAmount;

    @Nullable
    @Schema(example = "USD", description = "Null for sub-projects.")
    private String currency;

    /** Null for root projects; set for sub-projects (SHA256 uid of the parent). */
    @Nullable
    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa")
    private String parentProjectUid;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(example = "2025-01-10T00:00:00")
    private LocalDateTime createdAt;

    private List<MilestoneView> milestones;

    /** Sub-projects; empty for leaf nodes. */
    private List<ProjectView> subProjects;

}
