package org.cardanofoundation.lob.app.funding.domain.view;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** One failed CSV row (or row group) in a bulk-import file — skipped, not persisted. */
@Getter
@Builder
@AllArgsConstructor
public class FundingRowError {

    /** 1-based data row number within the file (header row excluded). */
    private int rowNumber;

    private String reason;

    /**
     * The underlying {@code ProblemDetail}'s machine-readable error code (see
     * {@code ErrorTitleConstants}), when the row failed a validation that carries one — absent for
     * file-level/parse errors that don't. Lets the frontend map a row error to its approved inline
     * validation message without pattern-matching {@code reason}, the same way it already does for
     * single-entry API responses via {@code ProblemDetail.title}.
     */
    private String title;

}
