package org.cardanofoundation.lob.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.cardanofoundation.lob.app.support.security.access.PermissionCatalogue;
import org.cardanofoundation.lob.app.support.security.access.PermissionCatalogueEnvironmentPostProcessor;

/**
 * The permission catalogue comes with the platform, the roles and permissions in Keycloak come from this repository.
 * When a new platform version adds a permission or a group, this fails until the Keycloak files have it too.
 */
class KeycloakPermissionsTest {

    private static final String REALM = "reeve-master";
    private static final String PERMISSIONS_CLIENT = "reeve-api";

    private final PermissionCatalogue catalogue = new PermissionCatalogue(PermissionCatalogueEnvironmentPostProcessor.shippedCatalogue());

    @ParameterizedTest
    @ValueSource(strings = {"keycloak-migrations/keycloak-config-cli/003-group-access-model.json", "keycloak-config/realm-export.json"})
    void keycloakHasExactlyThePermissionsOfTheCatalogue(String file) throws IOException {
        JsonNode realm = realm(file);

        assertThat(names(realm.path("roles").path("client").path(PERMISSIONS_CLIENT)))
                .containsExactlyInAnyOrderElementsOf(catalogue.getPermissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"keycloak-migrations/keycloak-config-cli/003-group-access-model.json", "keycloak-config/realm-export.json"})
    void keycloakHasTheRolesOfTheOrganisationGroups(String file) throws IOException {
        Set<String> rolesUsed = new LinkedHashSet<>(catalogue.getGroupLayout().values());
        rolesUsed.add(catalogue.getOrganisationRole());

        assertThat(names(realm(file).path("roles").path("realm"))).containsAll(rolesUsed);
    }

    /**
     * The migration holds one realm, the export a list of realms.
     */
    private static JsonNode realm(String file) throws IOException {
        JsonNode root = new ObjectMapper().readTree(repositoryFile(file).toFile());
        if (!root.isArray()) {
            return root;
        }
        return StreamSupport.stream(root.spliterator(), false)
                .filter(realm -> REALM.equals(realm.path("realm").asText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(file + " has no realm " + REALM));
    }

    private static Set<String> names(JsonNode roles) {
        Set<String> names = new LinkedHashSet<>();
        roles.forEach(role -> names.add(role.path("name").asText()));
        return names;
    }

    /**
     * Gradle runs the tests in cf-application, an IDE may run them in the repository root.
     */
    private static Path repositoryFile(String file) {
        Path fromModule = Path.of("..").resolve(file);
        return fromModule.toFile().exists() ? fromModule : Path.of(file);
    }

}
