package org.cardanofoundation.lob.app.organisation.service.csv;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

import org.springframework.http.ProblemDetail;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.request.EventCodeUpdate;
import org.cardanofoundation.lob.app.support.security.impl.ClamAVService;

@ExtendWith(MockitoExtension.class)
class CsvParserTest {
    @Mock
    private ClamAVService antiVirusScanner; // Mocked for the sake of the test, no actual scanning needed

    @InjectMocks
    private CsvParser<EventCodeUpdate> csvParser; // using EventCodeSince it's the easiest to mock

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        Field delimiterField = CsvParser.class.getDeclaredField("delimiter");
        delimiterField.setAccessible(true);
        delimiterField.set(csvParser, ",");
    }

    @Test
    void parseCsv_emptyFile() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);
        Either<ProblemDetail, List<EventCodeUpdate>> parse = csvParser.parseCsv(file, EventCodeUpdate.class);

        Assertions.assertTrue(parse.isLeft());
        Assertions.assertEquals("File is null", parse.getLeft().getDetail());

    }

    @Test
    void parseCsv_exception() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(antiVirusScanner.isFileSafe(any())).thenReturn(true);
        Either<ProblemDetail, List<EventCodeUpdate>> parse = csvParser.parseCsv(file, EventCodeUpdate.class);

        Assertions.assertTrue(parse.isLeft());
        Assertions.assertEquals("CSV_PARSING_ERROR", parse.getLeft().getTitle());
    }

    @Test
    void parseCsv_success() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);

        byte[] bytes = getClass()
                .getClassLoader()
                .getResourceAsStream("testData.csv") // adjust the path
                .readAllBytes();
        when(file.getBytes()).thenReturn(bytes);
        when(antiVirusScanner.isFileSafe(bytes)).thenReturn(true);
        Either<ProblemDetail, List<EventCodeUpdate>> parse = csvParser.parseCsv(file, EventCodeUpdate.class);

        Assertions.assertTrue(parse.isRight());
        Assertions.assertEquals(2, parse.get().size());
        EventCodeUpdate first = parse.get().getFirst();
        Assertions.assertEquals("123", first.getDebitReferenceCode());
        Assertions.assertEquals("456", first.getCreditReferenceCode());
        Assertions.assertEquals("Test Dummy", first.getName());

        EventCodeUpdate second = parse.get().get(1);
        Assertions.assertEquals("234", second.getDebitReferenceCode());
        Assertions.assertEquals("567", second.getCreditReferenceCode());
        Assertions.assertEquals("Test Dummy2", second.getName());
    }

    @Test
    void parseCsv_missingRequiredHeader() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);

        // "Credit Reference Code" header is missing.
        byte[] bytes = "Debit Reference Code,Name\n123,Test Dummy\n".getBytes();
        when(file.getBytes()).thenReturn(bytes);
        when(antiVirusScanner.isFileSafe(bytes)).thenReturn(true);

        Either<ProblemDetail, List<EventCodeUpdate>> parse = csvParser.parseCsv(file, EventCodeUpdate.class);

        Assertions.assertTrue(parse.isLeft());
        Assertions.assertEquals("CSV_HEADER_ERROR", parse.getLeft().getTitle());
        Assertions.assertTrue(parse.getLeft().getDetail().contains("Credit Reference Code"));
    }

    @Test
    void parseCsv_missingRequiredHeader_usesConfiguredDelimiterNotDefaultComma() throws NoSuchFieldException, IllegalAccessException, IOException {
        // Header check must read with the *configured* delimiter, same as the real parse below it —
        // using the wrong one either misreads a present header as missing, or a missing one as present.
        Field delimiterField = CsvParser.class.getDeclaredField("delimiter");
        delimiterField.setAccessible(true);
        delimiterField.set(csvParser, ";");

        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);

        // All three required headers are present, but semicolon-delimited.
        byte[] bytes = "Debit Reference Code;Credit Reference Code;Name\n123;456;Test Dummy\n".getBytes();
        when(file.getBytes()).thenReturn(bytes);
        when(antiVirusScanner.isFileSafe(bytes)).thenReturn(true);

        Either<ProblemDetail, List<EventCodeUpdate>> parse = csvParser.parseCsv(file, EventCodeUpdate.class);

        Assertions.assertTrue(parse.isRight(), () -> "expected success but got: " + parse.getLeft());
        Assertions.assertEquals(1, parse.get().size());
    }

    @Test
    void parseCsv_maliciousFile() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);

        byte[] bytes = new byte[1];
        when(file.getBytes()).thenReturn(bytes);
        when(antiVirusScanner.isFileSafe(bytes)).thenReturn(false);

        Either<ProblemDetail, List<EventCodeUpdate>> parse = csvParser.parseCsv(file, EventCodeUpdate.class);

        Assertions.assertTrue(parse.isLeft());
        Assertions.assertEquals("MALICIOUS_FILE_DETECTED", parse.getLeft().getTitle());
    }
}
