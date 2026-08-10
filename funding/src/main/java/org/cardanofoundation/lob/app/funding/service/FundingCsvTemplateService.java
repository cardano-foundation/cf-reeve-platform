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
    private static final String EXAMPLE_SUB_PROJECT_TITLE = "Sub One";
    private static final String EXAMPLE_MILESTONE_AMOUNT = "20000.00";
    private static final String EXAMPLE_SUB_PROJECT_TOTAL_AMOUNT = "40000.00";

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

    private void writeProjectsMilestonesTemplate(CSVWriter csvWriter) {
        csvWriter.writeNext(new String[]{
                COL_PROJECT_TITLE, "Parent Project Title", "Funding ID", "Total Amount", "Currency",
                COL_MILESTONE_TITLE, "Milestone Amount", "Milestone Currency", "Milestone Date"
        }, false);
        // Together these two milestones cover Sub One's full 40000.00 budget — the Events template
        // below funds and then spends both in full.
        csvWriter.writeNext(new String[]{
                "Project A", "", EXAMPLE_FUNDING_ID, "100000.00", "USD", "", "", "", ""
        }, false);
        csvWriter.writeNext(new String[]{
                EXAMPLE_SUB_PROJECT_TITLE, "Project A", "", EXAMPLE_SUB_PROJECT_TOTAL_AMOUNT, "USD",
                "Milestone One", EXAMPLE_MILESTONE_AMOUNT, "USD", "2026-06-30"
        }, false);
        csvWriter.writeNext(new String[]{
                EXAMPLE_SUB_PROJECT_TITLE, "Project A", "", "", "",
                "Milestone Two", EXAMPLE_MILESTONE_AMOUNT, "USD", "2026-07-15"
        }, false);
    }

    private void writeEventsTemplate(CSVWriter csvWriter) {
        csvWriter.writeNext(new String[]{
                "Event Type", "Funding ID", "Funding Hash", "Funding Entity", "Currency RCY", "Event Date",
                "Category", "Vendor", "Amount FCY", "Currency FCY", "FX Rate", "Amount RCY", "Hash", "Notes",
                COL_PROJECT_TITLE, COL_MILESTONE_TITLE, "Allocated Amount"
        }, false);
        // A full funding-then-spending example: one FUNDING event allocates 20000.00 to each of
        // Milestone One/Two (40000.00 total), and one SPENDING event later spends the same 40000.00
        // across the same two milestones — total funded == total spent, and each event's allocations
        // sum exactly to its own reporting-currency amount (required for SPENDING events).
        // Notes (like category/vendor/amounts) counts as spend detail and is rejected for non-SPENDING
        // events (FundingValidations.spendDetail), so FUNDING rows must leave every spend-detail column blank.
        csvWriter.writeNext(new String[]{
                "FUNDING", EXAMPLE_FUNDING_ID, "", "Cardano Foundation", "USD", "2026-07-01",
                "", "", "", "", "", "", "", "",
                EXAMPLE_SUB_PROJECT_TITLE, "Milestone One", EXAMPLE_MILESTONE_AMOUNT
        }, false);
        csvWriter.writeNext(new String[]{
                "FUNDING", EXAMPLE_FUNDING_ID, "", "Cardano Foundation", "USD", "2026-07-01",
                "", "", "", "", "", "", "", "",
                EXAMPLE_SUB_PROJECT_TITLE, "Milestone Two", EXAMPLE_MILESTONE_AMOUNT
        }, false);
        csvWriter.writeNext(new String[]{
                "SPENDING", EXAMPLE_FUNDING_ID, "", "", "USD", "2026-07-20",
                "Personnel", "Vendor AB", "36000.00", "EUR", "0.9", EXAMPLE_SUB_PROJECT_TOTAL_AMOUNT, "", "Invoice #INV-001",
                EXAMPLE_SUB_PROJECT_TITLE, "Milestone One", EXAMPLE_MILESTONE_AMOUNT
        }, false);
        csvWriter.writeNext(new String[]{
                "SPENDING", EXAMPLE_FUNDING_ID, "", "", "USD", "2026-07-20",
                "Personnel", "Vendor AB", "36000.00", "EUR", "0.9", EXAMPLE_SUB_PROJECT_TOTAL_AMOUNT, "", "Invoice #INV-001",
                EXAMPLE_SUB_PROJECT_TITLE, "Milestone Two", EXAMPLE_MILESTONE_AMOUNT
        }, false);
    }

}
