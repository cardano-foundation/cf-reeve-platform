package org.cardanofoundation.lob.app.support.security.access;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

/**
 * Used from {@code @PreAuthorize} on endpoints, with the {@code organisationId} the request acts on:
 * <pre>{@code @PreAuthorize("@permissions.can(#organisationId, 'reports', 'create')")}</pre>
 * The organisation is the request's own value (path, query, JSON body or multipart form), the same value the endpoint
 * reads and changes data with. A missing or blank value is refused, and there is deliberately no variant without an
 * organisation: an expression that doesn't pass one fails instead of granting access.
 * Platform-wide actions that don't belong to an organisation (creating one) use
 * <pre>{@code @PreAuthorize("@permissions.canPlatform('organisations', 'create')")}</pre>
 */
@Component("permissions")
@RequiredArgsConstructor
public class PermissionChecker {

    private final CurrentUserAccess currentUserAccess;

    public boolean can(String organisationId, String resource, String action) {
        if (organisationId == null || organisationId.isBlank()) {
            return false;
        }
        return currentUserAccess.get().organisation(organisationId)
                .map(organisation -> organisation.hasPermission(resource, action))
                .orElse(false);
    }

    public boolean canPlatform(String resource, String action) {
        return currentUserAccess.get().hasPlatformPermission(resource, action);
    }

}
