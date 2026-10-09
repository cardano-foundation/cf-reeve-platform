package org.cardanofoundation.lob.app.accounting_reporting_core.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Details;
import org.cardanofoundation.lob.app.accounting_reporting_core.utils.ErrorUtils;

@ExtendWith(MockitoExtension.class)
class ErrorUtilsTest {

    @Test
    void getBag_ProblemNull_ReturnsEmptyMap() {
        Map<String, Object> bag = ErrorUtils.getBag(null, null);
        assertTrue(bag.isEmpty());
    }

    @Test
    void getBag_catchingException() {
        ProblemDetail problem = mock(ProblemDetail.class);
        when(problem.getDetail()).thenThrow(new RuntimeException("Test Exception"));

        Map<String, Object> bag = ErrorUtils.getBag(problem, "TEST_CODE");
        assertEquals("An error occurred while processing the problem details", bag.get("error"));
    }

    @Test
    void getBag_fullFlow() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "This is a detailed error message.");
        problem.setTitle("Test Title");

        Map<String, Object> bag = ErrorUtils.getBag(problem, "TEST_CODE");

        assertEquals("This is a detailed error message.", bag.get("detail"));
        assertEquals("Test Title", bag.get("message"));

        Map<String, Object> errorMap = (Map<String, Object>) bag.get("error");
        assertNotNull(errorMap);
        assertEquals("TEST_CODE", errorMap.get("code"));
        assertEquals("This is a detailed error message.", errorMap.get("message"));

        assertEquals("This is a detailed error message.", bag.get("technicalErrorMessage"));
    }

    @Test
    void readableMessage_nullDetails_ReturnsEmpty() {
        assertEquals(Optional.empty(), ErrorUtils.readableMessage(null));
    }

    @Test
    void readableMessage_usesErrorMessage() {
        Details details = Details.builder()
                .code("ADAPTER_ERROR")
                .subCode("CSV_PARSING_ERROR")
                .bag(Map.of("error", Map.of("code", "CSV_PARSING_ERROR", "message", "Row 3 is invalid"), "detail", "other"))
                .build();

        assertEquals(Optional.of("Row 3 is invalid"), ErrorUtils.readableMessage(details));
    }

    @Test
    void readableMessage_skipsNonStringValues() {
        // The shape that crashed the UI: error.message expanded into an object
        Details details = Details.builder()
                .bag(Map.of(
                        "error", Map.of("message", Map.of("error", Map.of("code", "X"), "message", "")),
                        "detail", "NetSuite API error (HTTP 401): INVALID_LOGIN"))
                .build();

        assertEquals(Optional.of("NetSuite API error (HTTP 401): INVALID_LOGIN"), ErrorUtils.readableMessage(details));
    }

    @Test
    void readableMessage_usesTechnicalErrorMessageWhenNoErrorEntry() {
        Details details = Details.builder()
                .bag(Map.of("technicalErrorMessage", "Something failed", "organisationId", "org"))
                .build();

        assertEquals(Optional.of("Something failed"), ErrorUtils.readableMessage(details));
    }

    @Test
    void readableMessage_fallsBackToSubCodeThenCode() {
        assertEquals(Optional.of("ORGANISATION_NOT_IMPORTED"), ErrorUtils.readableMessage(Details.builder()
                .code("ADAPTER_ERROR").subCode("ORGANISATION_NOT_IMPORTED").bag(Map.of("subsidiary", 10L)).build()));
        assertEquals(Optional.of("ADAPTER_ERROR"), ErrorUtils.readableMessage(Details.builder()
                .code("ADAPTER_ERROR").subCode(" ").build()));
    }

    @Test
    void errorCode_prefersSubCode() {
        assertEquals(Optional.of("CLIENT_ERROR"), ErrorUtils.errorCode(Details.builder().code("ADAPTER_ERROR").subCode("CLIENT_ERROR").build()));
        assertEquals(Optional.of("ADAPTER_ERROR"), ErrorUtils.errorCode(Details.builder().code("ADAPTER_ERROR").build()));
        assertEquals(Optional.empty(), ErrorUtils.errorCode(null));
    }

    @Test
    void readableMessage_extractsJsonEmbeddedInAString() {
        Details details = Details.builder()
                .bag(Map.of("error", Map.of("message", "{\"error\":{\"code\":\"INVALID_LOGIN\",\"message\":\"Invalid login\"}}")))
                .build();

        assertEquals(Optional.of("INVALID_LOGIN - Invalid login"), ErrorUtils.readableMessage(details));
    }

    @Test
    void jsonErrorMessage_supportedShapes() {
        assertEquals(Optional.of("Failed: X - boom"), ErrorUtils.jsonErrorMessage("Failed: {\"error\":{\"code\":\"X\",\"message\":\"boom\"}}"));
        assertEquals(Optional.of("A - one; two"), ErrorUtils.jsonErrorMessage("{\"o:errorDetails\":[{\"o:errorCode\":\"A\",\"detail\":\"one\"},{\"detail\":\"two\"}]}"));
        assertEquals(Optional.of("Forbidden"), ErrorUtils.jsonErrorMessage("{\"title\":\"Forbidden\"}"));
        assertEquals(Optional.empty(), ErrorUtils.jsonErrorMessage("{\"foo\":1}"));
        assertEquals(Optional.empty(), ErrorUtils.jsonErrorMessage("plain {not json}"));
        assertEquals(Optional.empty(), ErrorUtils.jsonErrorMessage(null));
    }

    @Test
    void normalisedBag_alwaysHasTheSameShapeWithStringValues() {
        // Legacy NetSuite bag: no "error" entry, technicalErrorMessage is an object
        Details details = Details.builder()
                .code("ADAPTER_ERROR")
                .subCode("TRANSACTIONS_VALIDATION_ERROR")
                .bag(Map.of("technicalErrorMessage", Map.of("error", Map.of("code", "X")), "organisationId", "org"))
                .build();

        Map<String, Object> bag = ErrorUtils.normalisedBag(details);

        assertEquals(Map.of("code", "TRANSACTIONS_VALIDATION_ERROR", "message", "TRANSACTIONS_VALIDATION_ERROR"), bag.get("error"));
        assertEquals("TRANSACTIONS_VALIDATION_ERROR", bag.get("message"));
        assertEquals("TRANSACTIONS_VALIDATION_ERROR", bag.get("detail"));
        assertEquals("TRANSACTIONS_VALIDATION_ERROR", bag.get("technicalErrorMessage"));
        assertEquals(4, bag.size());
    }

    @Test
    void normalisedBag_keepsTheOriginalStringFields() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Row 3 is invalid");
        problem.setTitle("CSV_PARSING_ERROR");
        Details details = Details.builder()
                .code("ADAPTER_ERROR")
                .subCode("CSV_PARSING_ERROR")
                .bag(ErrorUtils.getBag(problem, "CSV_PARSING_ERROR"))
                .build();

        Map<String, Object> bag = ErrorUtils.normalisedBag(details);

        assertEquals(Map.of("code", "CSV_PARSING_ERROR", "message", "Row 3 is invalid"), bag.get("error"));
        assertEquals("CSV_PARSING_ERROR", bag.get("message"));
        assertEquals("Row 3 is invalid", bag.get("detail"));
        assertEquals("Row 3 is invalid", bag.get("technicalErrorMessage"));
    }
}
