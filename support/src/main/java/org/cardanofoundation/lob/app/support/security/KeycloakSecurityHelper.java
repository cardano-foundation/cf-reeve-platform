package org.cardanofoundation.lob.app.support.security;


import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;


@Component
public class KeycloakSecurityHelper {

    @Value("${keycloak.enabled:true}")
    private boolean keycloakEnabled;
    private final String SYSTEM_USER = "system";

    public boolean canUserAccessOrg(String orgId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // skipping if keycloak is not enabled
        if (!keycloakEnabled) {
            return true;
        }

        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            List<String> organisations = jwt.getClaimAsStringList("organisations");
            return organisations.contains(orgId);
        }
        return false;
    }

    public String getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("name");
        }
        return SYSTEM_USER;
    }

}
