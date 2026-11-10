package org.cardanofoundation.lob.app.support.security.access;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Resolves the organisations, roles, modules and permissions of an authenticated user.
 */
public interface UserAccessResolver {

    UserAccess resolve(Jwt jwt);

    /**
     * Forgets anything cached for this user, so the next request sees changes made in Keycloak.
     */
    default void evict(Jwt jwt) {
    }

}
