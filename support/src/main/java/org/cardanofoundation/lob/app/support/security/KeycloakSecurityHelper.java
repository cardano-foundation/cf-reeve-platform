package org.cardanofoundation.lob.app.support.security;


import lombok.RequiredArgsConstructor;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import org.cardanofoundation.lob.app.support.security.access.CurrentUserAccess;


@Component
@RequiredArgsConstructor
public class KeycloakSecurityHelper {

    private final String SYSTEM_USER = "system";

    private final CurrentUserAccess currentUserAccess;

    /**
     * The user must be a member of the organisation. Endpoints check it for the {@code organisationId} of the request,
     * the same value their permission is checked in and their data is read with.
     */
    public boolean canUserAccessOrg(String orgId) {
        return orgId != null && isMemberOfOrg(orgId);
    }

    /**
     * Membership of the organisation (e.g. to list the user's organisations).
     */
    public boolean isMemberOfOrg(String orgId) {
        return currentUserAccess.get().isMemberOf(orgId);
    }

    public String getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("name");
        }
        return SYSTEM_USER;
    }

    public String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("sub");
        }
        return SYSTEM_USER;
    }

}
