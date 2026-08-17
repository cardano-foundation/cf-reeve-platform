package org.cardanofoundation.lob.app.funding.domain.csv;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.opencsv.bean.CsvBindByName;

/**
 * One row of the Projects+Milestones bulk-import CSV. A row always carries the root project's
 * columns, and optionally a sub-project of that root ({@code sub*} columns) and/or one milestone
 * ({@code milestone*} columns) — the milestone belongs to the sub-project when one is present on
 * the same row, otherwise to the root. Consecutive rows sharing the same root {@code projectTitle}
 * are grouped into one project; each row's sub-project and milestone are then resolved
 * independently of the other rows in the group, one sub-project (or one milestone) per row.
 *
 * <p>Every row that declares a sub-project or a milestone carries the data needed to create it
 * directly, on that same row — so unlike a design that references another row's project by title,
 * row order within the file does not matter (a sub-project row doesn't need its root to have been
 * "seen" first; the root's own columns, wherever they appear in the group, are enough).
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ProjectMilestoneCsvLine {

    @CsvBindByName(column = "Project Title")
    private String projectTitle;

    @CsvBindByName(column = "Total Amount", profiles = "optional")
    private String totalAmount;

    @CsvBindByName(column = "Currency", profiles = "optional")
    private String currency;

    @CsvBindByName(column = "Sub Project Title", profiles = "optional")
    private String subProjectTitle;

    @CsvBindByName(column = "Sub Total Amount", profiles = "optional")
    private String subTotalAmount;

    @CsvBindByName(column = "Sub Currency", profiles = "optional")
    private String subCurrency;

    @CsvBindByName(column = "Milestone Title", profiles = "optional")
    private String milestoneTitle;

    @CsvBindByName(column = "Milestone Amount", profiles = "optional")
    private String milestoneAmount;

    @CsvBindByName(column = "Milestone Date", profiles = "optional")
    private String milestoneDate;

    public boolean hasSubProject() {
        return subProjectTitle != null && !subProjectTitle.isBlank();
    }

    /** Whether this row carries any of the root project's own creation/update columns. */
    public boolean hasRootProjectData() {
        return notBlank(totalAmount) || notBlank(currency);
    }

    public boolean hasMilestone() {
        return milestoneTitle != null && !milestoneTitle.isBlank();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
