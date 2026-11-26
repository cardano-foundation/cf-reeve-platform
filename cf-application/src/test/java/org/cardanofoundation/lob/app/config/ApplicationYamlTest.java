package org.cardanofoundation.lob.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.InputStream;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards the application configuration files against the failure mode that is invisible to a
 * Gradle build: a structurally invalid YAML file compiles and packages perfectly, then takes
 * down every service at startup.
 * <p>
 * Specifically, SnakeYAML rejects duplicate mapping keys. Two sibling {@code lob.security:}
 * blocks — easy to introduce when adding a property near an unrelated one — parse fine in most
 * editors and linters, but throw {@code DuplicateKeyException} on boot for the api, publisher
 * and lightweight deployments alike.
 */
class ApplicationYamlTest {

    @ParameterizedTest
    @ValueSource(strings = {"/application.yml", "/application-prod.yml", "/application-kafka-ssl.yml"})
    void parsesWithoutDuplicateKeys(String resource) {
        assertThatCode(() -> load(resource))
                .as("%s must parse under SnakeYAML — duplicate keys break startup for every service", resource)
                .doesNotThrowAnyException();
    }

    @Test
    void declaresTheConfigEncryptionKeyAlongsideTheOtherSecurityProperties() {
        Map<String, Object> root = load("/application.yml");

        @SuppressWarnings("unchecked")
        Map<String, Object> lob = (Map<String, Object>) root.get("lob");
        assertThat(lob).as("lob root").isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> security = (Map<String, Object>) lob.get("security");
        assertThat(security)
                .as("lob.security must exist exactly once and hold both config-encryption and clamav")
                .isNotNull()
                .containsKeys("config-encryption", "clamav");

        @SuppressWarnings("unchecked")
        Map<String, Object> configEncryption = (Map<String, Object>) security.get("config-encryption");
        assertThat(configEncryption)
                .as("asymmetric since 1.8: public key on the api tier, private key on the publisher")
                .containsKeys("public-key", "private-key")
                .doesNotContainKey("key");
    }

    @Test
    void bindsTheNetSuiteConfigurationTopicsUsedByTheKafkaBridge() {
        Map<String, Object> root = load("/application.yml");

        @SuppressWarnings("unchecked")
        Map<String, Object> lob = (Map<String, Object>) root.get("lob");

        @SuppressWarnings("unchecked")
        Map<String, Object> netsuite = (Map<String, Object>) lob.get("netsuite");
        @SuppressWarnings("unchecked")
        Map<String, Object> netsuiteTopics = (Map<String, Object>) netsuite.get("topics");
        assertThat(netsuiteTopics).containsKey("netsuite-config-upserted");

        @SuppressWarnings("unchecked")
        Map<String, Object> organisation = (Map<String, Object>) lob.get("organisation");
        @SuppressWarnings("unchecked")
        Map<String, Object> organisationTopics = (Map<String, Object>) organisation.get("topics");
        assertThat(organisationTopics).containsKey("netsuite-config-applied");
        assertThat(organisation).containsKey("consumer-group");
    }

    @Test
    void noLongerCarriesNetSuiteCredentialDefaults() {
        Map<String, Object> root = load("/application.yml");

        @SuppressWarnings("unchecked")
        Map<String, Object> lob = (Map<String, Object>) root.get("lob");
        @SuppressWarnings("unchecked")
        Map<String, Object> netsuite = (Map<String, Object>) lob.get("netsuite");
        @SuppressWarnings("unchecked")
        Map<String, Object> client = (Map<String, Object>) netsuite.get("client");

        assertThat(client)
                .as("credentials are per-organisation in the database now")
                .doesNotContainKeys("url", "token-url", "client-id", "certificate-id", "private-key-file-path")
                .as("operator tuning stays global")
                .containsKey("recordspercall");
    }

    @Test
    void leavesTheKeycloakConnectionAndThePermissionCatalogueToThePlatform() {
        Map<String, Object> root = load("/application.yml");

        @SuppressWarnings("unchecked")
        Map<String, Object> lob = (Map<String, Object>) root.get("lob");

        assertThat(lob)
                .as("lists set here would replace the platform's reeve-permissions.yml; the connection is set with LOB_KEYCLOAK_ADMIN_*")
                .doesNotContainKeys("keycloak", "permissions");
    }

    /**
     * Loads with {@code allowDuplicateKeys=false}, matching Spring Boot's {@code YamlProcessor}.
     * <p>
     * This is not optional: SnakeYAML's default {@code new Yaml()} <em>accepts</em> duplicate keys
     * and silently keeps the last one, so a test using the default constructor would pass against
     * a file that fails at boot — the exact failure this class exists to catch.
     */
    private Map<String, Object> load(String resource) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);

        try (InputStream in = ApplicationYamlTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing config resource on the classpath: " + resource);
            }

            return new Yaml(new SafeConstructor(options)).load(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read " + resource, e);
        }
    }

}
