package org.cardanofoundation.lob.app.funding.domain.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

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
    void detectsProjectsFile() {
        String header = "External Project ID;Project Title;Funding ID;Total Amount;Currency;"
                + "Parent External Project ID;Sub External Project ID;Sub Project Title;Sub Funding ID;"
                + "Sub Total Amount;Sub Currency\n";

        Optional<FundingCsvFileType> result = detector.detect(file(header));

        assertThat(result).contains(FundingCsvFileType.PROJECTS);
    }

    @Test
    void detectsMilestonesFile() {
        String header = "External Project ID;External Milestone ID;Milestone Title;Milestone Amount;"
                + "Currency;Milestone Date\n";

        Optional<FundingCsvFileType> result = detector.detect(file(header));

        assertThat(result).contains(FundingCsvFileType.MILESTONES);
    }

    @Test
    void detectsEventsFile() {
        String header = "Event Type;Funding ID;Funding Hash;Funding Entity;Currency RCY;Event Date;"
                + "Category;Vendor;Amount FCY;Currency FCY;FX Rate;Amount RCY;Hash;Notes;"
                + "External Project ID;External Milestone ID;Allocated Amount\n";

        Optional<FundingCsvFileType> result = detector.detect(file(header));

        assertThat(result).contains(FundingCsvFileType.EVENTS);
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

}
