package org.cardanofoundation.lob.app.reporting.util;

import java.util.List;

import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public class Helper {

    public static final List<String> FORBIDDEN_CHARACTERS = List.of(".", "$", "%", "#", "<", ">", "{", "}", "[", "]", "|", "^", "~");

    private Helper() {
        // Utility class
    }

    public static ProblemDetail buildDataModeError(String dataModeStr) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Data mode must be either SYSTEM or USER");
        if (dataModeStr == null || dataModeStr.isBlank()) {
            problemDetail.setTitle(Constants.DATA_MODE_MISSING);
            return problemDetail;
        }
        problemDetail.setTitle(Constants.INVALID_DATA_MODE);
        return problemDetail;
    }

    public static boolean containsForbiddenCharacters(@NotNull(message = "Field name must not be null") String fieldName) {
        return FORBIDDEN_CHARACTERS.stream().filter(fieldName::contains).findFirst().map(s -> true).orElse(false);
    }
}
