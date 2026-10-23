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
 * Generates the downloadable, blank CSV templates for the bulk importer — the standardized header
 * row only, no example data rows (a populated row would have to be manually cleaned out before a
 * user could fill in their own data, risking accidental data pollution during import).
 *
 * <p>The header row arrays are also reused by {@link FundingCsvExportService}, which writes the same
 * Projects+Milestones shape populated with the org's actual current data (including each row's
 * assigned {@code proId}) — kept here, not duplicated there, so the two can never drift apart.
 */
@Slf4j
@Service
public class FundingCsvTemplateService {

    static final String[] PROJECTS_MILESTONES_HEADER = {
            "Project Title", "Project ID", "Total Amount", "Currency",
            "Sub Project Title", "Sub Project ID", "Sub Total Amount",
            "Milestone Title", "Milestone ID", "Milestone Amount", "Milestone Date"
    };

    static final String[] EVENTS_HEADER = {
            "Event Type", "Funding ID", "Funding Hash", "Funding Entity", "Currency RCY", "Event Date",
            "Category", "Vendor", "Amount FCY", "Currency FCY", "FX Rate", "Amount RCY", "Hash", "Notes",
            "Project Title", "Project ID", "Sub Project Title", "Sub Project ID",
            "Milestone Title", "Milestone ID", "Allocated Amount"
    };

    public void writeTemplate(FundingCsvFileType type, OutputStream outputStream) {
        try (Writer writer = new OutputStreamWriter(outputStream)) {
            CSVWriter csvWriter = new CSVWriter(writer);
            switch (type) {
                case PROJECTS_MILESTONES -> csvWriter.writeNext(PROJECTS_MILESTONES_HEADER, false);
                case EVENTS -> csvWriter.writeNext(EVENTS_HEADER, false);
            }
            csvWriter.flush();
        } catch (IOException e) {
            log.error("Failed to write {} CSV template", type, e);
        }
    }

}
