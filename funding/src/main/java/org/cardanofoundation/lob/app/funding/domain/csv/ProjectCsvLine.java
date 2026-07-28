package org.cardanofoundation.lob.app.funding.domain.csv;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.opencsv.bean.CsvBindByName;

/**
 * One row of the Projects bulk-import CSV. A row always carries the root project's columns; the
 * optional {@code sub*} columns describe one sub-project of that root. Consecutive rows sharing the
 * same root key columns ({@code externalProjectId}) are grouped into one project with multiple
 * sub-projects — one sub-project per row in the group. A row with blank {@code sub*} columns
 * declares the root project alone (no sub-project on that row).
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ProjectCsvLine {

    @CsvBindByName(column = "External Project ID")
    private String externalProjectId;

    @CsvBindByName(column = "Project Title")
    private String projectTitle;

    @CsvBindByName(column = "Funding ID", profiles = "optional")
    private String fundingId;

    @CsvBindByName(column = "Total Amount", profiles = "optional")
    private String totalAmount;

    @CsvBindByName(column = "Currency", profiles = "optional")
    private String currency;

    /** Existing parent project's externalProjectId — set only when the root row is itself a sub-project. */
    @CsvBindByName(column = "Parent External Project ID", profiles = "optional")
    private String parentExternalProjectId;

    @CsvBindByName(column = "Sub External Project ID", profiles = "optional")
    private String subExternalProjectId;

    @CsvBindByName(column = "Sub Project Title", profiles = "optional")
    private String subProjectTitle;

    @CsvBindByName(column = "Sub Funding ID", profiles = "optional")
    private String subFundingId;

    @CsvBindByName(column = "Sub Total Amount", profiles = "optional")
    private String subTotalAmount;

    @CsvBindByName(column = "Sub Currency", profiles = "optional")
    private String subCurrency;

    public boolean hasSubProject() {
        return subExternalProjectId != null && !subExternalProjectId.isBlank();
    }
}
