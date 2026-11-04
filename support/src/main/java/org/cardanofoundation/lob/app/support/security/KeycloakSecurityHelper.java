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
     * The user must be a member of the organisation, and when the request names its organisation
     * ({@link CurrentUserAccess#ORGANISATION_HEADER}) it must be the same one, so permissions checked for the
     * header organisation can't be used on another organisation's data.
     */
    public boolean canUserAccessOrg(String orgId) {
        if (orgId == null || !isMemberOfOrg(orgId)) {
            return false;
        }
        return currentUserAccess.requestedOrganisationId()
                .map(orgId::equals)
                .orElse(true);
    }

    /**
     * Membership only, regardless of the organisation selected for the request (e.g. to list the user's organisations).
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
