package org.cardanofoundation.lob.app.support.security.access;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The part of the permission catalogue the backend needs, supplied by the application (lob.permissions).
 * Entries are lists rather than maps because Spring removes characters like "/" and "_" from map keys when binding.
 */
@ConfigurationProperties("lob.permissions")
public record PermissionProperties(List<String> modules, String organisationRole, List<Resource> resources, List<Group> groups, Platform platform) {

    public PermissionProperties {
        modules = orEmpty(modules);
        resources = orEmpty(resources);
        groups = orEmpty(groups);
    }

    public record Resource(String name, List<String> modules, List<String> actions) {

        public Resource {
            modules = orEmpty(modules);
            actions = orEmpty(actions);
        }

    }

    public record Group(String path, String role) {
    }

    public record Platform(String group) {
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

}
