package org.cardanofoundation.lob.app.support.security.access;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything the authenticated user can access, per organisation, plus platform-wide permissions that are not tied
 * to an organisation (e.g. "organisations:create").
 */
public record UserAccess(String username, Map<String, OrganisationAccess> organisations, Set<String> platformPermissions) {

    public UserAccess(String username, Map<String, OrganisationAccess> organisations) {
        this(username, organisations, Set.of());
    }

    public static UserAccess none(String username) {
        return new UserAccess(username, Map.of(), Set.of());
    }

    public boolean hasPlatformPermission(String resource, String action) {
        return platformPermissions.contains(PermissionCatalogue.permission(resource, action));
    }

    public boolean isMemberOf(String organisationId) {
        return organisations.containsKey(organisationId);
    }

    public Optional<OrganisationAccess> organisation(String organisationId) {
        return Optional.ofNullable(organisations.get(organisationId));
    }

}
