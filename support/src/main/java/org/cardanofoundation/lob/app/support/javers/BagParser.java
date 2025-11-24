package org.cardanofoundation.lob.app.support.javers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class BagParser {
    public static Map<String, Object> parse(Map<String, Object> bag) {
        return (Map<String, Object>) expandTree(new HashMap<>(bag));
    }

    private static Object expandTree(Object currentNode) {
        ObjectMapper objectMapper = new ObjectMapper();

        if (currentNode instanceof Map) {
            Map<String, Object> currentMap = (Map<String, Object>) currentNode;
            currentMap.replaceAll((key, value) -> expandTree(value));
            return currentMap;
        }
        else if (currentNode instanceof List) {
            List<Object> currentList = (List<Object>) currentNode;
            return currentList.stream()
                    .map(BagParser::expandTree)
                    .collect(Collectors.toList());
        }
        else if (currentNode instanceof String) {
            String currentString = (String) currentNode;
            int firstBrace = currentString.indexOf('{');
            int lastBrace = currentString.lastIndexOf('}');

            if (firstBrace != -1 && lastBrace > firstBrace) {
                String jsonCandidate = currentString.substring(firstBrace, lastBrace + 1);
                String prefixText = currentString.substring(0, firstBrace).trim();

                try {
                    Object parsedJson = objectMapper.readValue(jsonCandidate, Object.class);
                    if (parsedJson instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resultMap = (Map<String, Object>) parsedJson;
                        resultMap.put("message", prefixText);
                    }

                    return expandTree(parsedJson);
                } catch (JsonProcessingException e) {

                }
            }
            return currentString;
        }

        return currentNode;
    }
}
