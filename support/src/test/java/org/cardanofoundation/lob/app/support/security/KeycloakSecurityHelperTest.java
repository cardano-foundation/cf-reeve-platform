package org.cardanofoundation.lob.app.support.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;

import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class KeycloakSecurityHelperTest {

    private final KeycloakSecurityHelper helper = new KeycloakSecurityHelper();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateWith(Map<String, Object> claims) {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "RS256"), claims);
        TestingAuthenticationToken auth = new TestingAuthenticationToken(jwt, null);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void getCurrentUserIdReturnsSubClaim() {
        authenticateWith(Map.of("sub", "user-uuid-1", "name", "Alice"));
        assertEquals("user-uuid-1", helper.getCurrentUserId());
    }

    @Test
    void getCurrentUserIdFallsBackToSystemWhenUnauthenticated() {
        assertEquals("system", helper.getCurrentUserId());
    }

    @Test
    void canUserAccessOrgIsFalseWhenOrganisationsClaimMissing() {
        org.springframework.test.util.ReflectionTestUtils.setField(helper, "keycloakEnabled", true);
        authenticateWith(Map.of("sub", "user-uuid-1", "name", "Alice")); // no "organisations" claim
        org.junit.jupiter.api.Assertions.assertFalse(helper.canUserAccessOrg("org1"));
    }
}
