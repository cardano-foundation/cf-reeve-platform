package org.cardanofoundation.lob.app.funding.domain.csv;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.opencsv.bean.CsvBindByName;

/**
 * One row of the Projects+Milestones bulk-import CSV. A row always carries a project (root when
 * {@code parentProjectTitle} is blank, otherwise a sub-project of that parent) and optionally one
 * milestone of that same project. Consecutive rows sharing the same
 * ({@code parentProjectTitle}, {@code projectTitle}) pair are grouped into one project with
 * multiple milestones — one milestone per row in the group. A row with blank milestone columns
 * declares the project alone (no milestone on that row).
 *
 * <p>Unlike the old two-file (Projects / Milestones) shape, this format supports arbitrary-depth
 * nesting: {@code parentProjectTitle} may itself reference a sub-project created earlier (in this
 * same file, or previously), not just a root.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ProjectMilestoneCsvLine {

    @CsvBindByName(column = "Project Title")
    private String projectTitle;

    /** Blank for a root project; otherwise the title of an existing project (root or sub) this row nests under. */
    @CsvBindByName(column = "Parent Project Title", profiles = "optional")
    private String parentProjectTitle;

    @CsvBindByName(column = "Funding ID", profiles = "optional")
    private String fundingId;

    @CsvBindByName(column = "Total Amount", profiles = "optional")
    private String totalAmount;

    @CsvBindByName(column = "Currency", profiles = "optional")
    private String currency;

    @CsvBindByName(column = "Milestone Title", profiles = "optional")
    private String milestoneTitle;

    @CsvBindByName(column = "Milestone Amount", profiles = "optional")
    private String milestoneAmount;

    @CsvBindByName(column = "Milestone Currency", profiles = "optional")
    private String milestoneCurrency;

    @CsvBindByName(column = "Milestone Date", profiles = "optional")
    private String milestoneDate;

    public boolean isSubProject() {
        return parentProjectTitle != null && !parentProjectTitle.isBlank();
    }

    public boolean hasMilestone() {
        return milestoneTitle != null && !milestoneTitle.isBlank();
    }
}
