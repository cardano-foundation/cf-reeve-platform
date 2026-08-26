package org.cardanofoundation.lob.app.funding.domain.view;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * One CSV row (or row group) in a bulk-import file that was persisted successfully but flagged with a
 * non-blocking condition worth the caller's attention — currently only SPENDING budget overspend (see
 * {@code FundingValidations#isOverspend}). Unlike {@link FundingRowError}, a warning never causes the
 * row, its group, or the import to fail or roll back.
 */
@Getter
@Builder
@AllArgsConstructor
public class FundingRowWarning {

    /** 1-based data row number within the file (header row excluded). */
    private int rowNumber;

    private String reason;

}
