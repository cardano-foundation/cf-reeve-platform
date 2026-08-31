package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.opencsv.CSVReader;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.funding.domain.csv.FundingCsvFileType;

class FundingCsvTemplateServiceTest {

    private final FundingCsvTemplateService templateService = new FundingCsvTemplateService();

    private List<String[]> readRows(FundingCsvFileType type) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        templateService.writeTemplate(type, out);
        try (CSVReader reader = new CSVReader(new InputStreamReader(
                new ByteArrayInputStream(out.toByteArray()), StandardCharsets.UTF_8))) {
            return reader.readAll();
        }
    }

    @Test
    void projectsMilestonesTemplate_isJustTheHeaderRow_noExampleData() throws Exception {
        List<String[]> rows = readRows(FundingCsvFileType.PROJECTS_MILESTONES);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly("Project Title", "Total Amount", "Currency",
                "Sub Project Title", "Sub Total Amount",
                "Milestone Title", "Milestone Amount", "Milestone Date");
    }

    @Test
    void eventsTemplate_isJustTheHeaderRow_noExampleData() throws Exception {
        List<String[]> rows = readRows(FundingCsvFileType.EVENTS);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly("Event Type", "Funding ID", "Funding Hash", "Funding Entity",
                "Currency RCY", "Event Date", "Category", "Vendor", "Amount FCY", "Currency FCY", "FX Rate",
                "Amount RCY", "Hash", "Notes", "Project Title", "Sub Project Title", "Milestone Title", "Allocated Amount");
    }

    @Test
    void writeTemplate_logsAndDoesNotThrow_whenOutputStreamFails() {
        OutputStream failingStream = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk error");
            }
        };

        assertThatCode(() -> templateService.writeTemplate(FundingCsvFileType.PROJECTS_MILESTONES, failingStream))
                .doesNotThrowAnyException();
    }

}
