package org.cardanofoundation.lob.app.support.date;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import java.time.LocalDate;

import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class FlexibleDateParserTest {

    @Test
    void testParse() {
        // Test cases for different date formats
        String[] testDates = {
            "01/12/2023", // dd/MM/yyyy
            "12-01-2023", // MM-dd-yyyy
            "2023-01-12", // yyyy-MM-dd
            "01.12.2023"  // dd.MM.yyyy
        };

        for (String date : testDates) {
            try {
                LocalDate parse = FlexibleDateParser.parse(date);
                assertNotNull(parse);
            } catch (IllegalArgumentException e) {
                throw new AssertionError("Failed to parse date: " + date, e);
            }
        }
        // Test case for unsupported date format
        assertThrows(IllegalArgumentException.class, () -> {
            FlexibleDateParser.parse("Invalid date format");
        });

    }

}
