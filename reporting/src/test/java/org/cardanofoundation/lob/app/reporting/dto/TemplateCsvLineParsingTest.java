package org.cardanofoundation.lob.app.reporting.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.springframework.http.ProblemDetail;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.ObjectError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import io.vavr.control.Either;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.support.security.AntiVirusScanner;

/**
 * Parses real report template CSVs, to check the Standard column and its legacy Accounting Regime name
 * end to end: header check, binding and the row-level required validation.
 */
class TemplateCsvLineParsingTest {

    private static final String OTHER_HEADERS = "Name;Report Type;Generation Method;Active;Description;Field Name;Parent;Accounts;Date Range;Sign";
    private static final String OTHER_VALUES = "Template;Income statement;Manual;true;;Revenue;;;;";

    private CsvParser<TemplateCsvLine> csvParser;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        AntiVirusScanner antiVirusScanner = mock(AntiVirusScanner.class);
        when(antiVirusScanner.isFileSafe(any())).thenReturn(true);
        csvParser = new CsvParser<>(antiVirusScanner);
        ReflectionTestUtils.setField(csvParser, "delimiter", ";");
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
    }

    private Either<ProblemDetail, List<TemplateCsvLine>> parse(String standardHeaders, String standardValues) {
        String csv = OTHER_HEADERS + ";" + standardHeaders + "\n" + OTHER_VALUES + ";" + standardValues + "\n";
        return csvParser.parseCsv(csv.getBytes(), TemplateCsvLine.class);
    }

    private List<String> validationMessages(TemplateCsvLine line) {
        return validator.validateObject(line).getAllErrors().stream().map(ObjectError::getDefaultMessage).toList();
    }

    @Test
    void standardHeader_isRead() {
        Either<ProblemDetail, List<TemplateCsvLine>> result = parse("Standard", "IFRS");

        assertThat(result.isRight()).as(() -> "expected success but got: " + result.getLeft()).isTrue();
        assertThat(result.get().getFirst().getStandard()).isEqualTo("IFRS");
        assertThat(validationMessages(result.get().getFirst())).isEmpty();
    }

    @Test
    void legacyAccountingRegimeHeader_isStillRead() {
        Either<ProblemDetail, List<TemplateCsvLine>> result = parse("Accounting Regime", "IFRS");

        assertThat(result.isRight()).as(() -> "expected success but got: " + result.getLeft()).isTrue();
        assertThat(result.get().getFirst().getStandard()).isEqualTo("IFRS");
        assertThat(validationMessages(result.get().getFirst())).isEmpty();
    }

    @Test
    void bothHeaders_rejectFile() {
        Either<ProblemDetail, List<TemplateCsvLine>> result = parse("Standard;Accounting Regime", "IFRS;GAAP");

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("CSV_HEADER_ERROR");
        assertThat(result.getLeft().getDetail())
                .isEqualTo("Header row has both the Standard and the Accounting Regime column. Accounting Regime is the old name of Standard, keep only Standard.");
    }

    @Test
    void neitherHeader_reportsStandardMissing() {
        String csv = OTHER_HEADERS + "\n" + OTHER_VALUES + "\n";

        Either<ProblemDetail, List<TemplateCsvLine>> result = csvParser.parseCsv(csv.getBytes(), TemplateCsvLine.class);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("CSV_HEADER_ERROR");
        assertThat(result.getLeft().getDetail()).isEqualTo("Missing required headers: Standard");
    }

    @Test
    void emptyStandardCell_isRequired() {
        Either<ProblemDetail, List<TemplateCsvLine>> result = parse("Standard", "");

        assertThat(result.isRight()).isTrue();
        assertThat(validationMessages(result.get().getFirst())).containsExactly("Standard is required");
    }

    @Test
    void emptyLegacyCell_isRequiredUnderNewName() {
        Either<ProblemDetail, List<TemplateCsvLine>> result = parse("Accounting Regime", "");

        assertThat(result.isRight()).isTrue();
        assertThat(validationMessages(result.get().getFirst())).containsExactly("Standard is required");
    }
}
