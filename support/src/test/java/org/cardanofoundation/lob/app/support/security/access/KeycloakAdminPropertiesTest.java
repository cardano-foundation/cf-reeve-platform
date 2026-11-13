package org.cardanofoundation.lob.app.support.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import org.junit.jupiter.api.Test;

/**
 * The application sets no lob.keycloak.admin: the defaults are here, and deployments override them with the
 * LOB_KEYCLOAK_ADMIN_* environment variables they already use.
 */
class KeycloakAdminPropertiesTest {

    // Spring Boot's conversion service turns "PT30S" into a Duration, as it does in the application
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(KeycloakAdminProperties.class);

    @Test
    void defaultsToTheLocalKeycloak() {
        runner.run(context -> {
            KeycloakAdminProperties properties = context.getBean(KeycloakAdminProperties.class);
            assertThat(properties.getUrl()).isEqualTo("http://localhost:8080");
            assertThat(properties.getRealm()).isEqualTo("reeve-master");
            assertThat(properties.getClientId()).isEqualTo("reeve-backend");
            assertThat(properties.getClientSecret()).isEmpty();
            assertThat(properties.getPermissionsClientId()).isEqualTo("reeve-api");
            assertThat(properties.getOrganisationsGroup()).isEqualTo("organisations");
            assertThat(properties.getCacheTtl()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void readsTheEnvironmentVariablesDeploymentsSet() {
        runner.withInitializer(context -> {
                    context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource("testEnvironment", Map.of(
                            "LOB_KEYCLOAK_ADMIN_URL", "https://keycloak.example",
                            "LOB_KEYCLOAK_ADMIN_REALM", "other-realm",
                            "LOB_KEYCLOAK_ADMIN_CLIENT_ID", "other-backend",
                            "LOB_KEYCLOAK_ADMIN_CLIENT_SECRET", "secret",
                            "LOB_KEYCLOAK_ADMIN_PERMISSIONS_CLIENT_ID", "other-api",
                            "LOB_KEYCLOAK_ADMIN_ORGANISATIONS_GROUP", "orgs",
                            "LOB_KEYCLOAK_ADMIN_CACHE_TTL", "PT5M")));
                    ConfigurationPropertySources.attach(context.getEnvironment());
                })
                .run(context -> {
                    KeycloakAdminProperties properties = context.getBean(KeycloakAdminProperties.class);
                    assertThat(properties.getUrl()).isEqualTo("https://keycloak.example");
                    assertThat(properties.getRealm()).isEqualTo("other-realm");
                    assertThat(properties.getClientId()).isEqualTo("other-backend");
                    assertThat(properties.getClientSecret()).isEqualTo("secret");
                    assertThat(properties.getPermissionsClientId()).isEqualTo("other-api");
                    assertThat(properties.getOrganisationsGroup()).isEqualTo("orgs");
                    assertThat(properties.getCacheTtl()).isEqualTo(Duration.ofMinutes(5));
                });
    }

}
