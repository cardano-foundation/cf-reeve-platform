package org.cardanofoundation.lob.app.support.spring_web;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class SpringWebConfig {

    @Value("${lob.cors.allowed.origins:http://localhost:3000}")
    private String allowedOrigins;

    @Value("${keycloak.token-url}")
    private String tokenUrl;
    @Value("${keycloak.authorization-url}")
    private String authorizationUrl;

    private final OrganisationCheckInterceptor organisationCheckInterceptor;

    @PostConstruct
    public void init() {
        log.info("CORS configured allowed origins: {}", allowedOrigins);
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {

            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(organisationCheckInterceptor);
            }

            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(allowedOrigins.split(","))
                        .allowedMethods("GET", "HEAD", "POST") // expose only GET, HEAD, POST
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }

        };
    }

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .components(new Components()
                        .addSecuritySchemes("keycloakAuth", new SecurityScheme()
                                .name("keycloak")
                                .type(SecurityScheme.Type.OAUTH2)
                                .bearerFormat("JWT")
                                .scheme("bearer")
                                .flows(new OAuthFlows()
                                        .authorizationCode(new OAuthFlow()
                                                .authorizationUrl(authorizationUrl)
                                                .tokenUrl(tokenUrl)
                                                .scopes(new Scopes().addString("openid", "openid"))))))
                .addSecurityItem(new SecurityRequirement().addList("keycloakAuth"))
                .info(new Info().title("Lob Service")
                        .license(new License().name("Apache License 2.0")
                        .url("https://github.com/cardano-foundation/cf-lob/blob/main/LICENSE")));
    }

}
