package org.cardanofoundation.lob.app.accounting_reporting_core.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Details;

public class ErrorUtils {

    public static final String BAG_ERROR = "error";
    public static final String BAG_CODE = "code";
    public static final String BAG_MESSAGE = "message";
    public static final String BAG_DETAIL = "detail";
    public static final String BAG_TECHNICAL_ERROR_MESSAGE = "technicalErrorMessage";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
                error.put(BAG_CODE, code);
            }
            if (problem.getDetail() != null && !problem.getDetail().isEmpty()) {
                error.put(BAG_MESSAGE, problem.getDetail());
            }

            // Only add error map if it's not empty
            Map<String, Object> bag = new HashMap<>();
            if (problem.getDetail() != null && !problem.getDetail().isEmpty()) {
                bag.put(BAG_DETAIL, problem.getDetail());
            }
            if (problem.getTitle() != null && !problem.getTitle().isEmpty()) {
                bag.put(BAG_MESSAGE, problem.getTitle());
            }
            if (!error.isEmpty()) {
                bag.put(BAG_ERROR, error);
            }

            // Only include technicalErrorMessage if bag is not empty
            if (!bag.isEmpty() && problem.getDetail() != null && !problem.getDetail().isEmpty()) {
                bag.put(BAG_TECHNICAL_ERROR_MESSAGE, problem.getDetail());
            }

            return bag;
        } catch (Exception e) {
            return Map.of(BAG_ERROR, "An error occurred while processing the problem details");
        }
    }

    /**
     * The failure details of a batch in the one shape the UI reads, whatever the adapter stored:
     * {"error": {"code", "message"}, "message", "detail", "technicalErrorMessage"}, all plain strings.
     * Values that are not strings are dropped, and JSON embedded in a string (e.g. a raw NetSuite error
     * response) is turned into readable text.
     */
    public static Map<String, Object> normalisedBag(Details details) {
        Map<String, Object> bag = Optional.ofNullable(details.getBag()).orElse(Map.of());
        Optional<String> code = errorCode(details);
        Optional<String> message = readableMessage(details);

        Map<String, Object> error = new LinkedHashMap<>();
        code.ifPresent(value -> error.put(BAG_CODE, value));
        message.ifPresent(value -> error.put(BAG_MESSAGE, value));

        Map<String, Object> normalised = new LinkedHashMap<>();
        normalised.put(BAG_ERROR, error);
        firstText(bag.get(BAG_MESSAGE)).or(() -> message).ifPresent(value -> normalised.put(BAG_MESSAGE, value));
        firstText(bag.get(BAG_DETAIL)).or(() -> message).ifPresent(value -> normalised.put(BAG_DETAIL, value));
        firstText(bag.get(BAG_TECHNICAL_ERROR_MESSAGE), bag.get(BAG_DETAIL)).or(() -> message)
                .ifPresent(value -> normalised.put(BAG_TECHNICAL_ERROR_MESSAGE, value));

        return normalised;
    }

    /**
     * A single human-readable message for a failure {@link Details}. Only string values are considered;
     * falls back to the sub-code, then the code.
     */
    public static Optional<String> readableMessage(Details details) {
        if (details == null) {
            return Optional.empty();
        }
        Map<String, Object> bag = Optional.ofNullable(details.getBag()).orElse(Map.of());
        Object error = bag.get(BAG_ERROR);

        return firstText(
                error instanceof Map<?, ?> errorMap ? errorMap.get(BAG_MESSAGE) : error,
                bag.get(BAG_DETAIL),
                bag.get(BAG_TECHNICAL_ERROR_MESSAGE),
                bag.get(BAG_MESSAGE),
                details.getSubCode(),
                details.getCode());
    }

    /**
     * The most specific code of a failure {@link Details}: the sub-code when present, otherwise the code.
     */
    public static Optional<String> errorCode(Details details) {
        if (details == null) {
            return Optional.empty();
        }

        return Stream.of(details.getSubCode(), details.getCode())
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    /**
     * Readable text from an error payload in JSON, optionally preceded by plain text ("Error: {...}").
     * Understands {"error": {"code", "message"}}, {"o:errorDetails": [{"o:errorCode", "detail"}]} and
     * {"title"}; returns empty when the text holds no such JSON.
     */
    public static Optional<String> jsonErrorMessage(String text) {
        if (text == null) {
            return Optional.empty();
        }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace == -1 || lastBrace < firstBrace) {
            return Optional.empty();
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(text.substring(firstBrace, lastBrace + 1));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }

        List<String> messages = new ArrayList<>();
        JsonNode error = root.path(BAG_ERROR);
        if (error.isObject()) {
            messages.add(codeAndMessage(error.path(BAG_CODE).asText(""), error.path(BAG_MESSAGE).asText("")));
        } else if (error.isTextual()) {
            messages.add(error.asText());
        }
        root.path("o:errorDetails").forEach(detail ->
                messages.add(codeAndMessage(detail.path("o:errorCode").asText(""), detail.path(BAG_DETAIL).asText(""))));
        messages.removeIf(String::isBlank);
        if (messages.isEmpty() && !root.path("title").asText("").isBlank()) {
            messages.add(root.path("title").asText());
        }
        if (messages.isEmpty()) {
            return Optional.empty();
        }

        String prefix = text.substring(0, firstBrace).trim();
        String message = String.join("; ", messages);

        return Optional.of(prefix.isEmpty() ? message : prefix + " " + message);
    }

    private static Optional<String> firstText(Object... candidates) {
        return Stream.of(candidates)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> !value.isBlank())
                .map(value -> jsonErrorMessage(value).orElse(value))
                .findFirst();
    }

    private static String codeAndMessage(String code, String message) {
        if (code.isBlank()) {
            return message;
        }

        return message.isBlank() ? code : code + " - " + message;
    }
}
