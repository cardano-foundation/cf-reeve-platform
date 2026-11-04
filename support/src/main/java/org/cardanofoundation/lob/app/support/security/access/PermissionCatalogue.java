package org.cardanofoundation.lob.app.support.security.access;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The permissions ("resource:action") the platform knows about, the module(s) each resource belongs to and the
 * subgroup layout created for every organisation in Keycloak. The application supplies them as lob.permissions.
 * <p>
 * Endpoints declare the permission they need; who has it is decided by Keycloak (see {@link KeycloakUserAccessResolver}).
 * <p>
 * Without a catalogue the application still starts, so other modules and tests boot, but nobody gets any permission.
 */
@Component
@EnableConfigurationProperties(PermissionProperties.class)
@Slf4j
public class PermissionCatalogue {

    private static final String DEFAULT_PLATFORM_GROUP = "platform";

    private final Set<String> modules;
    private final Map<String, Set<String>> resourceModules;
    private final Set<String> permissions;
    private final String organisationRole;
    private final Map<String, String> groupLayout;
    private final String platformGroup;

    public PermissionCatalogue(PermissionProperties properties) {
        Map<String, Set<String>> modulesByResource = new LinkedHashMap<>();
        Set<String> allPermissions = new LinkedHashSet<>();
        properties.resources().forEach(resource -> {
            modulesByResource.put(resource.name(), Collections.unmodifiableSet(new LinkedHashSet<>(resource.modules())));
            resource.actions().forEach(action -> allPermissions.add(permission(resource.name(), action)));
        });

        Map<String, String> layout = new LinkedHashMap<>();
        properties.groups().forEach(group -> layout.put(group.path(), group.role()));

        this.modules = Collections.unmodifiableSet(new LinkedHashSet<>(properties.modules()));
        this.resourceModules = Collections.unmodifiableMap(modulesByResource);
        this.permissions = Collections.unmodifiableSet(allPermissions);
        this.organisationRole = properties.organisationRole();
        this.groupLayout = Collections.unmodifiableMap(layout);
        this.platformGroup = properties.platform() == null || properties.platform().group() == null ? DEFAULT_PLATFORM_GROUP : properties.platform().group();

        if (!isConfigured()) {
            log.error("lob.permissions is not set: nobody will get any permission and no organisation can be provisioned");
        }
    }

    public static String permission(String resource, String action) {
        return resource + ":" + action;
    }

    /**
     * @return false when the application supplied no catalogue, in which case no permission is granted
     */
    public boolean isConfigured() {
        return !permissions.isEmpty();
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
        if (!isConfigured()) {
            return Set.of();
        }
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

}
