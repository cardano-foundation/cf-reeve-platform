package org.cardanofoundation.lob.app.funding.domain.view;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.cardanofoundation.lob.app.funding.domain.csv.FundingCsvFileType;

/** Per-uploaded-file outcome of a bulk import: detected type, how many rows/groups saved, and errors. */
@Getter
@Builder
@AllArgsConstructor
public class FundingFileImportResult {

    private String fileName;

    /** Null when the file's type could not be determined — {@code rowErrors} then holds one file-level error. */
    private FundingCsvFileType fileType;

    private int rowsSucceeded;

    @Builder.Default
    private List<FundingRowError> rowErrors = new ArrayList<>();

}
