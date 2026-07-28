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

}
