package org.cardanofoundation.lob.app.funding.domain.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FundingCsvTypeDetectorTest {

    private FundingCsvTypeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new FundingCsvTypeDetector();
        ReflectionTestUtils.setField(detector, "delimiter", ";");
    }

    private MockMultipartFile file(String content) {
        return new MockMultipartFile("file", "upload.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void detectsProjectsMilestonesFile() {
        String header = "Project Title;Total Amount;Currency;"
                + "Sub Project Title;Sub Total Amount;"
                + "Milestone Title;Milestone Amount;Milestone Date\n";

        Optional<FundingCsvFileType> result = detector.detect(file(header));

        assertThat(result).contains(FundingCsvFileType.PROJECTS_MILESTONES);
    }

    @Test
    void detectsEventsFile() {
        String header = "Event Type;Funding ID;Funding Hash;Funding Entity;Currency RCY;Event Date;"
                + "Category;Vendor;Amount FCY;Currency FCY;FX Rate;Amount RCY;Hash;Notes;"
                + "Project Title;Milestone Title;Allocated Amount\n";

        Optional<FundingCsvFileType> result = detector.detect(file(header));

        assertThat(result).contains(FundingCsvFileType.EVENTS);
    }

    @Test
    void detectsProjectsMilestonesFileEvenWhenOnlyMandatoryColumnIsPresent() {
        // "Project Title" is the only mandatory column — a file with just that column (e.g. a
        // minimal export) must still be routed to this parser, not dropped as unrecognized.
        Optional<FundingCsvFileType> result = detector.detect(file("Project Title\n"));

        assertThat(result).contains(FundingCsvFileType.PROJECTS_MILESTONES);
    }

    @Test
    void detectsEventsFileEvenWhenMandatoryColumnIsMissing() {
        // "Funding ID" (mandatory) is missing.
        String header = "Event Type;Funding Hash;Funding Entity;Currency RCY;Event Date;"
                + "Category;Vendor;Amount FCY;Currency FCY;FX Rate;Amount RCY;Hash;Notes;"
                + "Project Title;Milestone Title;Allocated Amount\n";

        Optional<FundingCsvFileType> result = detector.detect(file(header));

        assertThat(result).contains(FundingCsvFileType.EVENTS);
    }

    @Test
    void projectsMilestonesFileWinsOverEventsFile_whenHeadersOverlapOnlyOnSharedColumns() {
        // "Project Title" and "Milestone Title" alone are a subset of both templates' declared
        // columns — the tighter-fitting (smaller declared column set) template wins.
        Optional<FundingCsvFileType> result = detector.detect(file("Project Title;Milestone Title\n"));

        assertThat(result).contains(FundingCsvFileType.PROJECTS_MILESTONES);
    }

    @Test
    void unrecognizedHeadersYieldEmpty() {
        Optional<FundingCsvFileType> result = detector.detect(file("Foo;Bar;Baz\n"));

        assertThat(result).isEmpty();
    }

    @Test
    void emptyFileYieldsEmpty() {
        Optional<FundingCsvFileType> result = detector.detect(file(""));

        assertThat(result).isEmpty();
    }

    @Test
    void unreadableFileYieldsEmpty_insteadOfThrowing() throws IOException {
        MultipartFile unreadable = mock(MultipartFile.class);
        when(unreadable.getBytes()).thenThrow(new IOException("disk error"));

        Optional<FundingCsvFileType> result = detector.detect(unreadable);

        assertThat(result).isEmpty();
    }

    @Test
    void missingHeadersIsEmpty_whenFileMatchesTemplateExactly() {
        String header = "Event Type;Funding ID;Funding Hash;Funding Entity;Currency RCY;Event Date;"
                + "Category;Vendor;Amount FCY;Currency FCY;FX Rate;Amount RCY;Hash;Notes;"
                + "Project Title;Sub Project Title;Milestone Title;Allocated Amount\n";

        Set<String> missing = detector.missingHeaders(file(header), FundingCsvFileType.EVENTS);

        assertThat(missing).isEmpty();
    }

    @Test
    void missingHeadersNamesTheOmittedOptionalColumn() {
        // "Amount FCY" (optional — only required for SPENDING events) is entirely absent from the
        // header row, not just blank on the data rows.
        String header = "Event Type;Funding ID;Funding Hash;Funding Entity;Currency RCY;Event Date;"
                + "Category;Vendor;Currency FCY;FX Rate;Amount RCY;Hash;Notes;"
                + "Project Title;Sub Project Title;Milestone Title;Allocated Amount\n";

        Set<String> missing = detector.missingHeaders(file(header), FundingCsvFileType.EVENTS);

        assertThat(missing).containsExactly("Amount FCY");
    }

    @Test
    void missingHeadersNamesEveryOmittedColumn_forProjectsMilestonesTemplate() {
        String header = "Project Title;Total Amount;Currency\n";

        Set<String> missing = detector.missingHeaders(file(header), FundingCsvFileType.PROJECTS_MILESTONES);

        assertThat(missing).containsExactlyInAnyOrder(
                "Sub Project Title", "Sub Total Amount", "Milestone Title", "Milestone Amount", "Milestone Date");
    }

}
