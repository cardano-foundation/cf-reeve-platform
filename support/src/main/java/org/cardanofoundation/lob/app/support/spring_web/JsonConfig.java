package org.cardanofoundation.lob.app.support.spring_web;

import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;

import java.io.IOException;
import java.math.BigDecimal;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Jackson wiring for the REST layer.
 *
 * <p>Both beans below must disable {@link
 * com.fasterxml.jackson.databind.SerializationFeature#WRITE_DATES_AS_TIMESTAMPS} explicitly. Jackson
 * enables it by default, rendering a {@code LocalDateTime} as a numeric array rather than an ISO-8601
 * string. Spring Boot normally disables it, but only on the {@code ObjectMapper} and
 * {@code Jackson2ObjectMapperBuilder} it creates itself, both of which are
 * {@code @ConditionalOnMissingBean} — so defining our own here means Boot's customisers never run.
 *
 * <p>Getting this wrong fails silently and application-wide: every {@code java.time} field in every
 * REST response degrades to a digit array no JSON client can parse as a date.
 */
@Configuration
@Slf4j
public class JsonConfig {

    @Bean
    public Jackson2ObjectMapperBuilder jackson2ObjectMapperBuilder() {
        log.info("Configuring Jackson2ObjectMapperBuilder");

        return new Jackson2ObjectMapperBuilder()
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .featuresToEnable(ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, FAIL_ON_UNKNOWN_PROPERTIES)
                .featuresToDisable(WRITE_DATES_AS_TIMESTAMPS)
                .modulesToInstall(new JavaTimeModule(), new Jdk8Module());
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.disable(WRITE_DATES_AS_TIMESTAMPS);

        SimpleModule bigDecimalModule = new SimpleModule();
        bigDecimalModule.addSerializer(BigDecimal.class, new JsonSerializer<BigDecimal>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value == null) {
                    gen.writeNull();
                } else {
                    gen.writeNumber(value.stripTrailingZeros().toPlainString());
                }
            }
        });
        objectMapper.registerModule(bigDecimalModule);

        objectMapper.findAndRegisterModules();

        log.info("Registered jackson modules:");
        objectMapper.getRegisteredModuleIds().forEach(moduleId -> {
            log.info("Module: {}", moduleId);
        });

        return objectMapper;
    }

}
