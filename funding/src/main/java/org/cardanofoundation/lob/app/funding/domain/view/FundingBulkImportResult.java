package org.cardanofoundation.lob.app.funding.domain.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.springframework.http.ProblemDetail;

/**
 * Overall bulk-import outcome. {@code error} is set only for request-level failures (e.g. no files
 * uploaded, organisation not found) — per-row and per-file failures are reported inside
 * {@code files} instead, alongside whatever did succeed, with an overall 200 OK.
 */
@Getter
@Builder
@AllArgsConstructor
public class FundingBulkImportResult implements ErrorAware {

    private boolean dryRun;

    @Builder.Default
    private List<FundingFileImportResult> files = new ArrayList<>();

    private int projectsCreated;
    private int subProjectsCreated;
    private int milestonesCreated;
    private int eventsCreated;
    private int allocationsCreated;

    private int projectsUpdated;
    private int subProjectsUpdated;
    private int milestonesUpdated;

    @Builder.Default
    private Optional<ProblemDetail> error = Optional.empty();

    public static FundingBulkImportResult error(ProblemDetail problem) {
        return FundingBulkImportResult.builder().error(Optional.of(problem)).build();
    }

    @Override
    public Optional<ProblemDetail> getError() {
        return error;
    }

}
