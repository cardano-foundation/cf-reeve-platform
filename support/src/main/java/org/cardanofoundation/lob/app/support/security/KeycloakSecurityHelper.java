package org.cardanofoundation.lob.app.support.security;


import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;


@Component
public class KeycloakSecurityHelper {

    public boolean canUserAccessOrg(String orgId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            List<String> organisations = jwt.getClaimAsStringList("organisations");
            return organisations.contains(orgId);
        }
        return false;
    }

}
