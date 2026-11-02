package org.cardanofoundation.lob.app.support.security.access;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The permissions ("resource:action") the platform knows about, the module(s) each resource belongs to and the
 * subgroup layout created for every organisation in Keycloak.
 * <p>
 * Endpoints declare the permission they need; who has it is decided by Keycloak (see {@link KeycloakUserAccessResolver}).
 * The roles and presets in the catalogue file are only used to seed and migrate Keycloak.
 */
@Component
public class PermissionCatalogue {

    private static final String CATALOGUE_FILE = "reeve-permissions.json";

    private final Set<String> modules;
    private final Map<String, Set<String>> resourceModules;
    private final Set<String> permissions;
    private final String organisationRole;
    private final Map<String, String> groupLayout;
    private final String platformGroup;

    @Autowired
    public PermissionCatalogue() {
        this(readCatalogue());
    }

    private PermissionCatalogue(JsonNode catalogue) {
        Set<String> moduleNames = new LinkedHashSet<>();
        catalogue.path("modules").forEach(module -> moduleNames.add(module.asText()));

        Map<String, Set<String>> modulesByResource = new LinkedHashMap<>();
        Set<String> allPermissions = new LinkedHashSet<>();
        catalogue.path("resources").properties().forEach(resourceEntry -> {
            String resource = resourceEntry.getKey();
            Set<String> resourceModuleNames = new LinkedHashSet<>();
            resourceEntry.getValue().path("modules").forEach(module -> resourceModuleNames.add(module.asText()));
            modulesByResource.put(resource, Collections.unmodifiableSet(resourceModuleNames));
            resourceEntry.getValue().path("actions").forEach(action -> allPermissions.add(permission(resource, action.asText())));
        });

        Map<String, String> layout = new LinkedHashMap<>();
        catalogue.path("groups").properties().forEach(group -> layout.put(group.getKey(), group.getValue().asText()));

        this.modules = Collections.unmodifiableSet(moduleNames);
        this.resourceModules = Collections.unmodifiableMap(modulesByResource);
        this.permissions = Collections.unmodifiableSet(allPermissions);
        this.organisationRole = catalogue.path("organisationRole").asText();
        this.groupLayout = Collections.unmodifiableMap(layout);
        this.platformGroup = catalogue.path("platform").path("group").asText("platform");
    }

    public static String permission(String resource, String action) {
        return resource + ":" + action;
    }

    /**
     * @return all modules that can be enabled or disabled per organisation
     */
    public Set<String> getModules() {
        return modules;
    }

    /**
     * @return the modules a resource belongs to; empty when it is part of the always-enabled core. A resource
     * shared by several modules is available when any of them is enabled.
     */
    public Set<String> modulesOf(String resource) {
        return resourceModules.getOrDefault(resource, Set.of());
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    /**
     * @return the realm role assigned on every organisation group, so all members of the organisation have it
     */
    public String getOrganisationRole() {
        return organisationRole;
    }

    /**
     * @return the subgroups created under each organisation group: relative path (e.g. "transactions/reviewer") to realm role
     */
    public Map<String, String> getGroupLayout() {
        return groupLayout;
    }

    /**
     * @return the top-level group holding platform-wide access (not tied to an organisation), e.g. /platform/administrators
     */
    public String getPlatformGroup() {
        return platformGroup;
    }

    /**
     * Drops permissions of modules that are not enabled.
     */
    public Set<String> filterByModules(Set<String> grantedPermissions, Set<String> enabledModules) {
        Set<String> result = new LinkedHashSet<>();
        for (String granted : grantedPermissions) {
            String resource = granted.contains(":") ? granted.substring(0, granted.indexOf(':')) : granted;
            Set<String> required = modulesOf(resource);
            if (required.isEmpty() || required.stream().anyMatch(enabledModules::contains)) {
                result.add(granted);
            }
        }
        return result;
    }

    private static JsonNode readCatalogue() {
        try (InputStream inputStream = new ClassPathResource(CATALOGUE_FILE).getInputStream()) {
            return new ObjectMapper().readTree(inputStream);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read the permission catalogue " + CATALOGUE_FILE, e);
        }
    }

}
