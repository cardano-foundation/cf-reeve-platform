package org.cardanofoundation.lob.app.accounting_reporting_core.utils;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ProblemDetail;

public class ErrorUtils {

    private ErrorUtils() {
        // Private constructor to prevent instantiation
    }

    public static Map<String, Object> getBag(ProblemDetail problem, String code) {
        try {
            if (problem == null) {
                return Map.of();
            }

            Map<String, Object> error = new HashMap<>();
            if (code != null && !code.isEmpty()) {
                error.put("code", code);
            }
            if (problem.getDetail() != null && !problem.getDetail().isEmpty()) {
                error.put("message", problem.getDetail());
            }

            // Only add error map if it's not empty
            Map<String, Object> bag = new HashMap<>();
            if (problem.getDetail() != null && !problem.getDetail().isEmpty()) {
                bag.put("detail", problem.getDetail());
            }
            if (problem.getTitle() != null && !problem.getTitle().isEmpty()) {
                bag.put("message", problem.getTitle());
            }
            if (!error.isEmpty()) {
                bag.put("error", error);
            }

            // Only include technicalErrorMessage if bag is not empty
            if (!bag.isEmpty() && problem.getDetail() != null && !problem.getDetail().isEmpty()) {
                bag.put("technicalErrorMessage", problem.getDetail());
            }

            return bag;
        } catch (Exception e) {
            return Map.of("error", "An error occurred while processing the problem details");
        }
    }
}
