package org.cardanofoundation.lob.app.funding.service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import com.opencsv.CSVWriter;

import org.cardanofoundation.lob.app.funding.domain.csv.FundingCsvFileType;

/**
 * Generates the downloadable CSV templates for the bulk importer — header row plus one example
 * data row per file type, following the same {@code CSVWriter}-over-{@code OutputStream} pattern
 * used by the organisation module's existing CSV download endpoints (e.g.
 * {@code ChartOfAccountsService.downloadCsv}).
 */
@Slf4j
@Service
public class FundingCsvTemplateService {

    private static final String COL_PROJECT_TITLE = "Project Title";
    private static final String COL_MILESTONE_TITLE = "Milestone Title";
    private static final String EXAMPLE_FUNDING_ID = "GRANT-2025-001";
    private static final String MILESTONE_AMOUNT = "20000.00";

    public void writeTemplate(FundingCsvFileType type, OutputStream outputStream) {
        try (Writer writer = new OutputStreamWriter(outputStream)) {
            CSVWriter csvWriter = new CSVWriter(writer);
            switch (type) {
                case PROJECTS_MILESTONES -> writeProjectsMilestonesTemplate(csvWriter);
                case EVENTS -> writeEventsTemplate(csvWriter);
            }
            csvWriter.flush();
        } catch (IOException e) {
            log.error("Failed to write {} CSV template", type, e);
        }
    }

    /**
     * Covers the three shapes a project/milestone row can take: a root-only declaration (Project A's
     * first row: no sub-project, no milestone), a sub-project with its own milestones — with the
     * <em>same</em> milestone titles reused across sibling sub-projects Sub One/Sub Two (allowed:
     * milestone titles are unique per project, not across siblings), and a standalone root with a
     * milestone directly on it, no sub-project (Project B). The root's own columns and a sub-project's
     * columns are always on the same row as each other (never split across rows referencing one
     * another by title), so row order within the file never matters.
     */
    private void writeProjectsMilestonesTemplate(CSVWriter csvWriter) {
        csvWriter.writeNext(new String[]{
                COL_PROJECT_TITLE, "Funding ID", "Total Amount", "Currency",
                "Sub Project Title", "Sub Funding ID", "Sub Total Amount", "Sub Currency",
                COL_MILESTONE_TITLE, "Milestone Amount", "Milestone Date"
        }, false);
        csvWriter.writeNext(new String[]{
                "Project A", EXAMPLE_FUNDING_ID, "100000.00", "USD", "", "", "", "", "", "", ""
        }, false);
        csvWriter.writeNext(new String[]{
                "Project A", "", "", "", "Sub One", "", "40000.00", "USD", "Milestone One", MILESTONE_AMOUNT, "2026-06-30"
        }, false);
        csvWriter.writeNext(new String[]{
                "Project A", "", "", "", "Sub One", "", "", "", "Milestone Two", MILESTONE_AMOUNT, "2026-07-15"
        }, false);
        csvWriter.writeNext(new String[]{
                "Project A", "", "", "", "Sub Two", "", "40000.00", "USD", "Milestone One", MILESTONE_AMOUNT, "2026-06-30"
        }, false);
        csvWriter.writeNext(new String[]{
                "Project A", "", "", "", "Sub Two", "", "", "", "Milestone Two", MILESTONE_AMOUNT, "2026-07-15"
        }, false);
        csvWriter.writeNext(new String[]{
                "Project B", "GRANT-2025-002", "20000.00", "USD", "", "", "", "", "Milestone One", MILESTONE_AMOUNT, "2026-06-30"
        }, false);
    }

    /**
     * A full funding-then-spending example over the project tree from the Projects+Milestones
     * template above: one FUNDING event allocates 20000.00 to each of the five milestones (Sub
     * One/Sub Two's Milestone One+Two, and Project B's Milestone One — 100000.00 total, matching the
     * combined budget of Sub One + Sub Two + Project B), and one SPENDING event later spends the same
     * 100000.00 across the same five — total funded == total spent, and each event's allocations sum
     * exactly to its own reporting-currency amount (required for SPENDING events). A milestone is
     * always referenced together with its owning Project Title, so the same milestone title under Sub
     * One and under Sub Two is never ambiguous.
     *
     * <p>Notes (like category/vendor/amounts) counts as spend detail and is rejected for non-SPENDING
     * events (FundingValidations.spendDetail), so FUNDING rows must leave every spend-detail column blank.
     */
    private void writeEventsTemplate(CSVWriter csvWriter) {
        csvWriter.writeNext(new String[]{
                "Event Type", "Funding ID", "Funding Hash", "Funding Entity", "Currency RCY", "Event Date",
                "Category", "Vendor", "Amount FCY", "Currency FCY", "FX Rate", "Amount RCY", "Hash", "Notes",
                COL_PROJECT_TITLE, COL_MILESTONE_TITLE, "Allocated Amount"
        }, false);
        for (String[] target : new String[][]{{"Sub One", "Milestone One"}, {"Sub One", "Milestone Two"},
                {"Sub Two", "Milestone One"}, {"Sub Two", "Milestone Two"}, {"Project B", "Milestone One"}}) {
            csvWriter.writeNext(new String[]{
                    "FUNDING", EXAMPLE_FUNDING_ID, "", "Cardano Foundation", "USD", "2026-07-01",
                    "", "", "", "", "", "", "", "",
                    target[0], target[1], MILESTONE_AMOUNT
            }, false);
        }
        for (String[] target : new String[][]{{"Sub One", "Milestone One"}, {"Sub One", "Milestone Two"},
                {"Sub Two", "Milestone One"}, {"Sub Two", "Milestone Two"}, {"Project B", "Milestone One"}}) {
            csvWriter.writeNext(new String[]{
                    "SPENDING", EXAMPLE_FUNDING_ID, "", "", "USD", "2026-07-20",
                    "Personnel", "Vendor AB", "90000.00", "EUR", "0.9", "100000.00", "Invoice #INV-001", "",
                    target[0], target[1], MILESTONE_AMOUNT
            }, false);
        }
    }

}
