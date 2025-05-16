package org.cardanofoundation.lob.app.organisation.service.csv;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.request.EventCodeUpdate;

@ExtendWith(MockitoExtension.class)
class CsvParserTest {

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
        Either<Problem, List<EventCodeUpdate>> parse = csvParser.parseCsv(file, EventCodeUpdate.class);

        Assertions.assertTrue(parse.isLeft());
        Assertions.assertEquals("File is null", parse.getLeft().getDetail());

    }

    @Test
    void parseCsv_exception() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        Either<Problem, List<EventCodeUpdate>> parse = csvParser.parseCsv(file, EventCodeUpdate.class);

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
        Either<Problem, List<EventCodeUpdate>> parse = csvParser.parseCsv(file, EventCodeUpdate.class);

        Assertions.assertTrue(parse.isRight());
        Assertions.assertEquals(2, parse.get().size());
        EventCodeUpdate first = parse.get().getFirst();
        Assertions.assertEquals("123", first.getDebitReferenceCode());
        Assertions.assertEquals("456", first.getCreditReferenceCode());
        Assertions.assertEquals("Test Dummy", first.getName());
        Assertions.assertTrue(first.getActive());

        EventCodeUpdate second = parse.get().get(1);
        Assertions.assertEquals("234", second.getDebitReferenceCode());
        Assertions.assertEquals("567", second.getCreditReferenceCode());
        Assertions.assertEquals("Test Dummy2", second.getName());
        Assertions.assertFalse(second.getActive());
    }
}
