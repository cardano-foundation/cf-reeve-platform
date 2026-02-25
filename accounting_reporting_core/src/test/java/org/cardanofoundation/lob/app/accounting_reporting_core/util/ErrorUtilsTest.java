package org.cardanofoundation.lob.app.accounting_reporting_core.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

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
}
