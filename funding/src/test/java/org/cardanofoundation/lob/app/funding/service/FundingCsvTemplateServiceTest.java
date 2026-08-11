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
    void projectsMilestonesTemplate_hasHeaderAndExpectedRows() throws Exception {
        List<String[]> rows = readRows(FundingCsvFileType.PROJECTS_MILESTONES);

        assertThat(rows).hasSize(7); // header + root-only + 2x Sub One + 2x Sub Two + Project B
        assertThat(rows.get(0)).containsExactly("Project Title", "Funding ID", "Total Amount", "Currency",
                "Sub Project Title", "Sub Funding ID", "Sub Total Amount", "Sub Currency",
                "Milestone Title", "Milestone Amount", "Milestone Date");

        // Root-only declaration row: no sub-project, no milestone.
        assertThat(rows.get(1)).containsExactly("Project A", "GRANT-2025-001", "100000.00", "USD",
                "", "", "", "", "", "", "");
        // Sub One's two milestones — root columns left blank (already declared on row 1).
        assertThat(rows.get(2)).containsExactly("Project A", "", "", "",
                "Sub One", "", "40000.00", "USD", "Milestone One", "20000.00", "2026-06-30");
        assertThat(rows.get(3)).containsExactly("Project A", "", "", "",
                "Sub One", "", "", "", "Milestone Two", "20000.00", "2026-07-15");
        // Sub Two reuses the same milestone titles as Sub One — allowed, since uniqueness is per project.
        assertThat(rows.get(4)).containsExactly("Project A", "", "", "",
                "Sub Two", "", "40000.00", "USD", "Milestone One", "20000.00", "2026-06-30");
        assertThat(rows.get(5)).containsExactly("Project A", "", "", "",
                "Sub Two", "", "", "", "Milestone Two", "20000.00", "2026-07-15");
        // Project B: standalone root with a milestone directly on it, no sub-project.
        assertThat(rows.get(6)).containsExactly("Project B", "GRANT-2025-002", "20000.00", "USD",
                "", "", "", "", "Milestone One", "20000.00", "2026-06-30");
    }

    @Test
    void projectsMilestonesTemplate_subProjectMilestonesSumToTheirOwnBudget() throws Exception {
        List<String[]> rows = readRows(FundingCsvFileType.PROJECTS_MILESTONES);

        // column indices: 6=Sub Total Amount, 9=Milestone Amount
        double subOneTotal = Double.parseDouble(rows.get(2)[6]);
        double subOneMilestonesSum = Double.parseDouble(rows.get(2)[9]) + Double.parseDouble(rows.get(3)[9]);
        double subTwoTotal = Double.parseDouble(rows.get(4)[6]);
        double subTwoMilestonesSum = Double.parseDouble(rows.get(4)[9]) + Double.parseDouble(rows.get(5)[9]);

        assertThat(subOneMilestonesSum).isEqualTo(subOneTotal);
        assertThat(subTwoMilestonesSum).isEqualTo(subTwoTotal);
    }

    @Test
    void projectsMilestonesTemplate_sameMilestoneTitleReusedAcrossDifferentSubProjects() throws Exception {
        List<String[]> rows = readRows(FundingCsvFileType.PROJECTS_MILESTONES);

        // Sub One's "Milestone One" (row 2) and Sub Two's "Milestone One" (row 4) share a title but
        // belong to different (sibling) sub-projects — demonstrating this is allowed.
        assertThat(rows.get(2)[4]).isEqualTo("Sub One");
        assertThat(rows.get(4)[4]).isEqualTo("Sub Two");
        assertThat(rows.get(2)[8]).isEqualTo(rows.get(4)[8]).isEqualTo("Milestone One");
    }

    @Test
    void eventsTemplate_hasHeaderAndTenExampleRows_fundingThenSpendingAllFiveMilestones() throws Exception {
        List<String[]> rows = readRows(FundingCsvFileType.EVENTS);

        assertThat(rows).hasSize(11); // header + 5 FUNDING + 5 SPENDING
        assertThat(rows.get(0)).containsExactly("Event Type", "Funding ID", "Funding Hash", "Funding Entity",
                "Currency RCY", "Event Date", "Category", "Vendor", "Amount FCY", "Currency FCY", "FX Rate",
                "Amount RCY", "Hash", "Notes", "Project Title", "Milestone Title", "Allocated Amount");

        // FUNDING rows: every spend-only column (Category, Vendor, Amount FCY, Currency FCY, FX Rate,
        // Hash, Notes) must stay blank — FundingValidations.spendDetail rejects any of them being set
        // on a non-SPENDING event. Amount RCY is required for every event type, so it's populated with
        // the event's total (100000.00) on every row of the FUNDING group.
        List<String[]> fundingRows = rows.subList(1, 6);
        assertThat(fundingRows).extracting(r -> r[0]).containsOnly("FUNDING");
        assertThat(fundingRows).extracting(r -> r[11]).containsOnly("100000.00");
        assertThat(fundingRows).extracting(r -> new String[]{r[14], r[15]}).containsExactly(
                new String[]{"Sub One", "Milestone One"}, new String[]{"Sub One", "Milestone Two"},
                new String[]{"Sub Two", "Milestone One"}, new String[]{"Sub Two", "Milestone Two"},
                new String[]{"Project B", "Milestone One"});

        List<String[]> spendingRows = rows.subList(6, 11);
        assertThat(spendingRows).extracting(r -> r[0]).containsOnly("SPENDING");
        assertThat(spendingRows).extracting(r -> new String[]{r[14], r[15]}).containsExactly(
                new String[]{"Sub One", "Milestone One"}, new String[]{"Sub One", "Milestone Two"},
                new String[]{"Sub Two", "Milestone One"}, new String[]{"Sub Two", "Milestone Two"},
                new String[]{"Project B", "Milestone One"});
    }

    @Test
    void eventsTemplate_totalFundedMatchesTotalSpent_andMatchesTheProjectTree() throws Exception {
        List<String[]> rows = readRows(FundingCsvFileType.EVENTS);

        double totalFunded = rows.subList(1, 6).stream().mapToDouble(r -> Double.parseDouble(r[16])).sum();
        double totalSpent = rows.subList(6, 11).stream().mapToDouble(r -> Double.parseDouble(r[16])).sum();
        // The SPENDING event's own reporting-currency amount must also equal what's allocated —
        // required by FundingValidations.spendFullyAllocated.
        double spendingEventAmountRcy = Double.parseDouble(rows.get(6)[11]);

        assertThat(totalFunded).isEqualTo(totalSpent);
        assertThat(totalSpent).isEqualTo(spendingEventAmountRcy);
        // Matches the combined budget of Sub One (40000) + Sub Two (40000) + Project B (20000) from
        // the Projects+Milestones template.
        assertThat(totalFunded).isEqualTo(100000.00);
    }

    /**
     * Regression test for a real bug: the FUNDING example rows used to set {@code Notes}, which
     * {@link FundingValidations#spendDetail} treats as spend-only detail — forbidden for any
     * non-SPENDING event. This runs the real validation against each event row's columns (row layout:
     * 0=Event Type,4=Currency RCY,6=Category,7=Vendor,8=Amount FCY,9=Currency FCY,10=FX Rate,
     * 11=Amount RCY,12=Hash,13=Notes) so any future template edit that reintroduces spend-only data on
     * a FUNDING/REFUND row fails here instead of at upload time.
     */
    @Test
    void fundingEventExampleRows_passRealSpendDetailValidation() throws Exception {
        List<String[]> rows = readRows(FundingCsvFileType.EVENTS);

        for (String[] row : rows.subList(1, 6)) {
            Optional<ProblemDetail> problem = FundingValidations.spendDetail(EventType.FUNDING,
                    blankToNull(row[6]), blankToNull(row[7]), decimal(row[8]), blankToNull(row[9]),
                    decimal(row[10]), decimal(row[11]), row[4], blankToNull(row[12]), blankToNull(row[13]));

            assertThat(problem).isEmpty();
        }
    }

    @Test
    void spendingEventExampleRows_passRealSpendDetailValidation() throws Exception {
        List<String[]> rows = readRows(FundingCsvFileType.EVENTS);

        for (String[] row : rows.subList(6, 11)) {
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

        assertThatCode(() -> templateService.writeTemplate(FundingCsvFileType.PROJECTS_MILESTONES, failingStream))
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
