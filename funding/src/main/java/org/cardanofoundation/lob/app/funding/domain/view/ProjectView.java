package org.cardanofoundation.lob.app.funding.domain.view;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import lombok.*;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Builder
@AllArgsConstructor
public class ProjectView {

    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa")
    private String projectId;

    @Schema(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94", description = "Organisation ID")
    private String organisationId;

    @Schema(example = "GRANT-2025-001")
    private String fundingId;

    @Schema(example = "PROJ-AB")
    private String activityId;

    @Schema(example = "Project AB")
    private String activityTitle;

    @Schema(example = "200000.00")
    private BigDecimal expectedTotalAmount;

    @Schema(example = "USD")
    private String currency;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(example = "2025-01-10T00:00:00")
    private LocalDateTime createdAt;

    private List<MilestoneView> milestones;

    private List<FundingEventView> events;

}
