package org.cardanofoundation.lob.app.support.security.access;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

/**
 * Used from {@code @PreAuthorize} on endpoints:
 * <pre>{@code @PreAuthorize("@permissions.can('reports', 'create')")}</pre>
 * The permission is checked in the organisation of the current request (see {@link CurrentUserAccess}).
 * Platform-wide actions that don't belong to an organisation (creating one) use
 * <pre>{@code @PreAuthorize("@permissions.canPlatform('organisations', 'create')")}</pre>
 */
@Component("permissions")
@RequiredArgsConstructor
public class PermissionChecker {

    private final CurrentUserAccess currentUserAccess;

    public boolean can(String resource, String action) {
        return currentUserAccess.currentOrganisation()
                .map(organisation -> organisation.hasPermission(resource, action))
                .orElse(false);
    }

    public boolean canPlatform(String resource, String action) {
        return currentUserAccess.get().hasPlatformPermission(resource, action);
    }

}
