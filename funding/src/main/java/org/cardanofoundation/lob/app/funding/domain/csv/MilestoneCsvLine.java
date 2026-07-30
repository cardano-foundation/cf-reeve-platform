package org.cardanofoundation.lob.app.funding.domain.csv;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.opencsv.bean.CsvBindByName;

/**
 * One row of the Milestones bulk-import CSV — one milestone per row, attached to a project or
 * sub-project already created (by an earlier row in this same import's Projects file, or already
 * existing) and referenced by its {@code externalProjectId}.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class MilestoneCsvLine {

    @CsvBindByName(column = "External Project ID")
    private String externalProjectId;

    @CsvBindByName(column = "External Milestone ID", profiles = "optional")
    private String externalMilestoneId;

    @CsvBindByName(column = "Milestone Title")
    private String milestoneTitle;

    @CsvBindByName(column = "Milestone Amount")
    private String milestoneAmount;

    @CsvBindByName(column = "Currency")
    private String currency;

    @CsvBindByName(column = "Milestone Date")
    private String milestoneDate;
}
