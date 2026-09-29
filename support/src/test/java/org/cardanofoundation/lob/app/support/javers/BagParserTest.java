package org.cardanofoundation.lob.app.support.javers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BagParserTest {

    @Test
    void expandsJsonEmbeddedInStringsAndKeepsThePrefixAsMessage() {
        Map<String, Object> bag = Map.of("technicalErrorMessage", "Failed: {\"code\":\"X\"}");

        Map<String, Object> parsed = BagParser.parse(bag);

        assertThat(parsed.get("technicalErrorMessage")).isEqualTo(Map.of("code", "X", "message", "Failed:"));
    }

    @Test
    void doesNotModifyTheInputBag() {
        String message = "Failed: {\"code\":\"X\"}";
        Map<String, Object> error = new HashMap<>(Map.of("message", message));
        Map<String, Object> bag = new HashMap<>(Map.of("error", error, "issues", List.of(new HashMap<>(Map.of("detail", message)))));

        BagParser.parse(bag);

        assertThat(error).containsEntry("message", message);
        assertThat(bag.get("issues")).isEqualTo(List.of(Map.of("detail", message)));
    }
}
