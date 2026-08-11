package org.cardanofoundation.lob.app.keri_attestation.cesr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Splits and reassembles CESR streams: {@code {json event}{attachment}{json event}{attachment}...}.
 *
 * <p>Vendored deliberately. This came from signify-java's {@code CESRStreamUtil}, which exists only on
 * that project's unmerged {@code feat/verify} branch — never upstream. Upstream signify has no CESR
 * stream parser at all, so tracking upstream means owning this rather than pinning a fork build to
 * keep one small utility.
 *
 * <p>Copied rather than rewritten so the framing stays byte-identical to what produced the credential
 * chains already in circulation. It depends on nothing but {@code java.util} plus Jackson, which is
 * what makes owning it cheap. The indexer carries the same file for the same reason.
 */
public class CESRStreamUtil {

    /** Compact, insertion-ordered output — the framing must round-trip byte-for-byte, so no pretty
     *  printing and no key reordering. Events are parsed into LinkedHashMaps to preserve order. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Parses CESR format string into an array of events with their attachments.
     * CESR format: {json_event}{attachment}{json_event}{attachment}...
     *
     * @param cesrData The CESR format string
     * @return List of maps containing "event" and "atc" keys
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseCESRData(String cesrData) {
        List<Map<String, Object>> result = new ArrayList<>();

        int index = 0;
        while (index < cesrData.length()) {
            // Find the start of JSON event (look for opening brace)
            if (cesrData.charAt(index) == '{') {
                // Find the end of JSON event by counting braces
                int braceCount = 0;
                int jsonStart = index;
                int jsonEnd = index;

                for (int i = index; i < cesrData.length(); i++) {
                    char ch = cesrData.charAt(i);
                    if (ch == '{') {
                        braceCount++;
                    } else if (ch == '}') {
                        braceCount--;
                        if (braceCount == 0) {
                            jsonEnd = i + 1;
                            break;
                        }
                    }
                }

                // Extract JSON event
                String jsonEvent = cesrData.substring(jsonStart, jsonEnd);

                // Find attachment data (everything until next '{' or end of string)
                int attachmentStart = jsonEnd;
                int attachmentEnd = cesrData.length();

                for (int i = attachmentStart; i < cesrData.length(); i++) {
                    if (cesrData.charAt(i) == '{') {
                        attachmentEnd = i;
                        break;
                    }
                }

                String attachment = "";
                if (attachmentStart < attachmentEnd) {
                    attachment = cesrData.substring(attachmentStart, attachmentEnd);
                }

                // Parse JSON event to Object
                try {
                    Map<String, Object> eventObj = readEvent(jsonEvent);

                    Map<String, Object> eventMap = new LinkedHashMap<>();
                    eventMap.put("event", eventObj);
                    eventMap.put("atc", attachment);
                    result.add(eventMap);
                } catch (Exception e) {
                    System.err.println("Failed to parse JSON event: " + jsonEvent);
                    e.printStackTrace();
                }

                index = attachmentEnd;
            } else {
                index++;
            }
        }

        return result;
    }

    /**
     * Reconstructs a CESR format stream from parsed events and their attachments.
     * @param parsedData List of maps containing "event" and "atc" keys
     * @return A CESR format string with events and attachments concatenated
     */
    @SuppressWarnings("unchecked")
    public static String makeCESRStream(List<Map<String, Object>> parsedData) {
        StringBuilder cesrStream = new StringBuilder();

        for (Map<String, Object> eventData : parsedData) {
            Map<String, Object> event = (Map<String, Object>) eventData.get("event");
            String attachment = (String) eventData.get("atc");

            if (event != null) {
                String jsonEvent = writeEvent(event);
                cesrStream.append(jsonEvent);
            }

            if (attachment != null && !attachment.isEmpty()) {
                cesrStream.append(attachment);
            }
        }

        return cesrStream.toString();
    }

    /**
     * Reconstructs a CESR format stream from separate lists of events and attachments.
     * @param events List of event maps (VCP, ISS, ACDC events)
     * @param attachments List of attachment strings corresponding to each event (can be null for events without attachments)
     * @return A CESR format string with events and attachments concatenated
     * @throws IllegalArgumentException if events and attachments lists have different sizes
     */
    public static String makeCESRStream(List<Map<String, Object>> events, List<String> attachments) {
        if (events.size() != attachments.size()) {
            throw new IllegalArgumentException(
                "Events and attachments lists must have the same size. " +
                "Events: " + events.size() + ", Attachments: " + attachments.size()
            );
        }

        List<Map<String, Object>> parsedData = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> eventMap = new LinkedHashMap<>();
            eventMap.put("event", events.get(i));
            eventMap.put("atc", attachments.get(i) != null ? attachments.get(i) : "");
            parsedData.add(eventMap);
        }

        return makeCESRStream(parsedData);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readEvent(String json) {
        try {
            return JSON.readValue(json, LinkedHashMap.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not a JSON event in the CESR stream: " + e.getMessage(), e);
        }
    }

    private static String writeEvent(Map<String, Object> event) {
        try {
            return JSON.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event cannot be serialised back to CESR: " + e.getMessage(), e);
        }
    }
}
