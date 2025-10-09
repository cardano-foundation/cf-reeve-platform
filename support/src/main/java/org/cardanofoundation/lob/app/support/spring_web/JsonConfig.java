package org.cardanofoundation.lob.app.support.spring_web;

import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;

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
import org.zalando.problem.jackson.ProblemModule;

@Configuration
@Slf4j
public class JsonConfig {

    @Bean
    public Jackson2ObjectMapperBuilder jackson2ObjectMapperBuilder(ProblemModule problem) {
        log.info("Configuring Jackson2ObjectMapperBuilder");

        return new Jackson2ObjectMapperBuilder()
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .featuresToEnable(ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, FAIL_ON_UNKNOWN_PROPERTIES)
                .modulesToInstall(new JavaTimeModule(), new Jdk8Module(), problem);
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

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
