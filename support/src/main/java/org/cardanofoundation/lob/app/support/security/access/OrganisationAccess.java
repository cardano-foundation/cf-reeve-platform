package org.cardanofoundation.lob.app.support.security.access;

import java.util.Set;

/**
 * What a user can do inside one organisation.
 *
 * @param organisationId the organisation
 * @param roles          the roles the user holds in this organisation
 * @param modules        the modules enabled for this organisation
 * @param permissions    the effective permissions ("resource:action"), already limited to the enabled modules
 */
public record OrganisationAccess(String organisationId, Set<String> roles, Set<String> modules, Set<String> permissions) {

    public boolean hasPermission(String resource, String action) {
        return permissions.contains(PermissionCatalogue.permission(resource, action));
    }

}
