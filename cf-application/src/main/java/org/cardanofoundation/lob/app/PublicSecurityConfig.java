package org.cardanofoundation.lob.app;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;

@Configuration(proxyBeanMethods = false)
public class PublicSecurityConfig {

    @Bean
    WebSecurityCustomizer publicWebSecurityCustomizer() {
        return web -> web.ignoring().requestMatchers(
                "/swagger-ui/**",
                "/swagger-ui.html",
                "/v3/api-docs",
                "/v3/api-docs/**",
                "/actuator/health",
                "/actuator/health/liveness",
                "/api/v1/mockresult",
                "/actuator/health/readiness");
    }
}
