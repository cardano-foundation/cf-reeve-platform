package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.springframework.http.ProblemDetail;

import com.opencsv.CSVReader;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.funding.domain.csv.FundingCsvFileType;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.util.FundingValidations;

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
    void projectsTemplate_hasHeaderAndOneExampleRow() throws Exception {
        List<String[]> rows = readRows(FundingCsvFileType.PROJECTS);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("External Project ID", "Project Title", "Funding ID",
                "Total Amount", "Currency", "Parent External Project ID", "Sub External Project ID",
                "Sub Project Title", "Sub Funding ID", "Sub Total Amount", "Sub Currency");
        assertThat(rows.get(1)).containsExactly("PROJ-A", "Project A", "GRANT-2025-001", "100000.00", "USD",
                "", "SUB-1", "Sub One", "", "40000.00", "USD");
    }

    @Test
    void milestonesTemplate_hasHeaderAndTwoExampleRows_coveringSub1sFullBudget() throws Exception {
        List<String[]> rows = readRows(FundingCsvFileType.MILESTONES);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsExactly("External Project ID", "External Milestone ID", "Milestone Title",
                "Milestone Amount", "Currency", "Milestone Date");
        assertThat(rows.get(1)).containsExactly("SUB-1", "MS-1", "Milestone One", "20000.00", "USD", "2026-06-30");
        assertThat(rows.get(2)).containsExactly("SUB-1", "MS-2", "Milestone Two", "20000.00", "USD", "2026-07-15");

        // The two milestones together exactly cover SUB-1's 40000.00 budget (see the Projects template).
        double sum = Double.parseDouble(rows.get(1)[3]) + Double.parseDouble(rows.get(2)[3]);
        assertThat(sum).isEqualTo(40000.00);
    }

    @Test
    void eventsTemplate_hasHeaderAndFourExampleRows_fundingThenSpendingBothMilestones() throws Exception {
        List<String[]> rows = readRows(FundingCsvFileType.EVENTS);

        assertThat(rows).hasSize(5);
        assertThat(rows.get(0)).containsExactly("Event Type", "Funding ID", "Funding Hash", "Funding Entity",
                "Currency RCY", "Event Date", "Category", "Vendor", "Amount FCY", "Currency FCY", "FX Rate",
                "Amount RCY", "Hash", "Notes", "External Project ID", "External Milestone ID", "Allocated Amount");

        // Two FUNDING rows (one per milestone), grouped into a single FUNDING event. Every spend-detail
        // column (including Notes) must stay blank — FundingValidations.spendDetail rejects any of
        // them being set on a non-SPENDING event.
        assertThat(rows.get(1)).containsExactly("FUNDING", "GRANT-2025-001", "", "Cardano Foundation", "USD",
                "2026-07-01", "", "", "", "", "", "", "", "", "SUB-1", "MS-1", "20000.00");
        assertThat(rows.get(2)).containsExactly("FUNDING", "GRANT-2025-001", "", "Cardano Foundation", "USD",
                "2026-07-01", "", "", "", "", "", "", "", "", "SUB-1", "MS-2", "20000.00");

        // Two SPENDING rows (one per milestone), grouped into a single SPENDING event.
        assertThat(rows.get(3)).containsExactly("SPENDING", "GRANT-2025-001", "", "", "USD", "2026-07-20",
                "Personnel", "Vendor AB", "36000.00", "EUR", "0.9", "40000.00", "", "Invoice #INV-001",
                "SUB-1", "MS-1", "20000.00");
        assertThat(rows.get(4)).containsExactly("SPENDING", "GRANT-2025-001", "", "", "USD", "2026-07-20",
                "Personnel", "Vendor AB", "36000.00", "EUR", "0.9", "40000.00", "", "Invoice #INV-001",
                "SUB-1", "MS-2", "20000.00");
    }

    @Test
    void eventsTemplate_totalFundedMatchesTotalSpent() throws Exception {
        List<String[]> rows = readRows(FundingCsvFileType.EVENTS);

        double totalFunded = Double.parseDouble(rows.get(1)[16]) + Double.parseDouble(rows.get(2)[16]);
        double totalSpent = Double.parseDouble(rows.get(3)[16]) + Double.parseDouble(rows.get(4)[16]);
        // The SPENDING event's own reporting-currency amount must also equal what's allocated —
        // required by FundingValidations.spendFullyAllocated.
        double spendingEventAmountRcy = Double.parseDouble(rows.get(3)[11]);

        assertThat(totalFunded).isEqualTo(totalSpent);
        assertThat(totalSpent).isEqualTo(spendingEventAmountRcy);
    }

    /**
     * Regression test for a real bug: the FUNDING example rows used to set {@code Notes}, which
     * {@link FundingValidations#spendDetail} treats as spend-only detail — forbidden for any
     * non-SPENDING event. This runs the real validation against each event group's columns (event
     * row layout: 0=Event Type,4=Currency RCY,6=Category,7=Vendor,8=Amount FCY,9=Currency FCY,
     * 10=FX Rate,11=Amount RCY,12=Hash,13=Notes) so any future template edit that reintroduces
     * spend-only data on a FUNDING/REFUND row fails here instead of at upload time.
     */
    @Test
    void fundingEventExampleRows_passRealSpendDetailValidation() throws Exception {
        List<String[]> rows = readRows(FundingCsvFileType.EVENTS);

        for (String[] row : List.of(rows.get(1), rows.get(2))) {
            Optional<ProblemDetail> problem = FundingValidations.spendDetail(EventType.FUNDING,
                    blankToNull(row[6]), blankToNull(row[7]), decimal(row[8]), blankToNull(row[9]),
                    decimal(row[10]), decimal(row[11]), row[4], blankToNull(row[12]), blankToNull(row[13]));

            assertThat(problem).isEmpty();
        }
    }

    @Test
    void spendingEventExampleRows_passRealSpendDetailValidation() throws Exception {
        List<String[]> rows = readRows(FundingCsvFileType.EVENTS);

        for (String[] row : List.of(rows.get(3), rows.get(4))) {
            Optional<ProblemDetail> problem = FundingValidations.spendDetail(EventType.SPENDING,
                    blankToNull(row[6]), blankToNull(row[7]), decimal(row[8]), blankToNull(row[9]),
                    decimal(row[10]), decimal(row[11]), row[4], blankToNull(row[12]), blankToNull(row[13]));

            assertThat(problem).isEmpty();
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static BigDecimal decimal(String s) {
        return (s == null || s.isBlank()) ? null : new BigDecimal(s);
    }

    @Test
    void writeTemplate_logsAndDoesNotThrow_whenOutputStreamFails() {
        OutputStream failingStream = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk error");
            }
        };

        assertThatCode(() -> templateService.writeTemplate(FundingCsvFileType.PROJECTS, failingStream))
                .doesNotThrowAnyException();
    }

    @Test
    void everyTemplateExampleRowMatchesItsOwnHeaderColumnCount() throws Exception {
        for (FundingCsvFileType type : FundingCsvFileType.values()) {
            List<String[]> rows = readRows(type);
            for (int i = 1; i < rows.size(); i++) {
                assertThat(rows.get(i)).as("%s example row %d column count", type, i).hasSameSizeAs(rows.get(0));
            }
        }
    }

}
