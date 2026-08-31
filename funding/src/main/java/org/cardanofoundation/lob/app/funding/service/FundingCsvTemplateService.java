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
 */
@Slf4j
@Service
public class FundingCsvTemplateService {

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
                "Project Title", "Total Amount", "Currency",
                "Sub Project Title", "Sub Total Amount",
                "Milestone Title", "Milestone Amount", "Milestone Date"
        }, false);
    }

    private void writeEventsTemplate(CSVWriter csvWriter) {
        csvWriter.writeNext(new String[]{
                "Event Type", "Funding ID", "Funding Hash", "Funding Entity", "Currency RCY", "Event Date",
                "Category", "Vendor", "Amount FCY", "Currency FCY", "FX Rate", "Amount RCY", "Hash", "Notes",
                "Project Title", "Sub Project Title", "Milestone Title", "Allocated Amount"
        }, false);
    }

}
