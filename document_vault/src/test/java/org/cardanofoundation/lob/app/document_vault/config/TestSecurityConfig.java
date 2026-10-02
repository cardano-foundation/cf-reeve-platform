package org.cardanofoundation.lob.app.document_vault.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Test-profile stand-in for the {@code keycloak.enabled=false} switch that release/1.8.0 removed.
 *
 * <p>The integration tests here exercise document_vault's own behaviour, not authentication: they used
 * to run with security disabled, which both opened the filter chain and made
 * {@link KeycloakSecurityHelper#canUserAccessOrg} return {@code true}. This restores exactly those two
 * effects for the {@code test} profile only — the permit-all chain mirrors support's
 * {@code DevSecurityConfiguration} ({@code insecure} profile). Picked up by the
 * {@code org.cardanofoundation.lob} component scan in {@code DocumentVaultContextIntegrationTest.TestConfig}.
 * Unit tests that assert org-access denial mock {@link KeycloakSecurityHelper} directly and are unaffected.
 */
@Configuration
@Profile("test")
public class TestSecurityConfig {

    @Bean
    public SecurityFilterChain testPermitAllFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Primary
    public KeycloakSecurityHelper testKeycloakSecurityHelper() {
        return new KeycloakSecurityHelper() {
            @Override
            public boolean canUserAccessOrg(String orgId) {
                return true;
            }
        };
    }
}
