package org.cardanofoundation.lob.app.support.spring_web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Regression test for a silent, app-wide serialisation defect: {@code java.time} values were rendering
 * as numeric arrays rather than ISO-8601 strings, because both Jackson beans in {@link JsonConfig}
 * bypass Spring Boot's date defaults (Boot's own beans are {@code @ConditionalOnMissingBean}) and
 * Jackson's own default for {@code WRITE_DATES_AS_TIMESTAMPS} is ENABLED.
 *
 * <p>It surfaced in the UI as a document {@code createdAt} of {@code 20267271493301717000} — the digits
 * of {@code [2026,7,27,14,9,33,...]} concatenated by the renderer.
 */
class JsonConfigDateFormatTest {

    private final JsonConfig jsonConfig = new JsonConfig();

    @Test
    void objectMapperBeanSerialisesLocalDateTimeAsIsoString() throws JsonProcessingException {
        ObjectMapper mapper = jsonConfig.objectMapper();

        String json = mapper.writeValueAsString(LocalDateTime.of(2026, 7, 27, 14, 9, 33));

        assertThat(json).isEqualTo("\"2026-07-27T14:09:33\"");
    }

    @Test
    void objectMapperBeanSerialisesLocalDateAsIsoString() throws JsonProcessingException {
        ObjectMapper mapper = jsonConfig.objectMapper();

        String json = mapper.writeValueAsString(LocalDate.of(2026, 7, 27));

        assertThat(json).isEqualTo("\"2026-07-27\"");
    }

    /**
     * The builder bean feeds Spring MVC's message converter, so it must agree with the mapper bean —
     * a mismatch would make the wire format depend on which one a given code path happens to use.
     */
    @Test
    void builderBeanSerialisesLocalDateTimeAsIsoString() throws JsonProcessingException {
        ObjectMapper mapper = jsonConfig.jackson2ObjectMapperBuilder().build();

        String json = mapper.writeValueAsString(LocalDateTime.of(2026, 7, 27, 14, 9, 33));

        assertThat(json).isEqualTo("\"2026-07-27T14:09:33\"");
    }

    /** Guards the actual defect: a digit-only payload means the array form is back. */
    @Test
    void neitherBeanEmitsANumericArrayForLocalDateTime() throws JsonProcessingException {
        LocalDateTime at = LocalDateTime.of(2026, 7, 27, 14, 9, 33, 1_717_000);

        for (ObjectMapper mapper : new ObjectMapper[] {
                jsonConfig.objectMapper(), jsonConfig.jackson2ObjectMapperBuilder().build() }) {
            String json = mapper.writeValueAsString(at);

            assertThat(json).doesNotStartWith("[");
            assertThat(json).startsWith("\"");
        }
    }
}
