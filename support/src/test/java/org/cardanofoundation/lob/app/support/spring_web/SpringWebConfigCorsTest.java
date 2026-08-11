package org.cardanofoundation.lob.app.support.spring_web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import org.mockito.Mockito;

import org.junit.jupiter.api.Test;

class SpringWebConfigCorsTest {

    /**
     * document_vault exposes PUT (addressbook, records) and DELETE (addressbook, keys) endpoints;
     * browsers preflight those methods cross-origin, so the global CORS mapping must allow them or
     * the UI's mutating calls fail even though the endpoints exist.
     */
    @Test
    void corsMappingAllowsAllMethodsUsedByTheApi() {
        SpringWebConfig config = new SpringWebConfig(Mockito.mock(OrganisationCheckInterceptor.class));
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:3000");

        WebMvcConfigurer configurer = config.corsConfigurer();
        CorsRegistry registry = new CorsRegistry();
        configurer.addCorsMappings(registry);

        @SuppressWarnings("unchecked")
        Map<String, CorsConfiguration> configurations =
                (Map<String, CorsConfiguration>) ReflectionTestUtils.invokeMethod(registry, "getCorsConfigurations");

        assertThat(configurations).containsKey("/api/**");
        assertThat(configurations.get("/api/**").getAllowedMethods())
                .contains("GET", "HEAD", "POST", "PUT", "DELETE");
        assertThat(configurations.get("/api/**").getAllowedOrigins()).contains("http://localhost:3000");
    }
}
