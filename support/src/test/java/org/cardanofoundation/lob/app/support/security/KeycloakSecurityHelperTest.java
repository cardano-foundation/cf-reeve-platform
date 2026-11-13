package org.cardanofoundation.lob.app.support.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.support.security.access.CurrentUserAccess;
import org.cardanofoundation.lob.app.support.security.access.UserAccess;

class KeycloakSecurityHelperTest {

    private final CurrentUserAccess currentUserAccess = mock(CurrentUserAccess.class);
    private final KeycloakSecurityHelper helper = new KeycloakSecurityHelper(currentUserAccess);

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
    void canUserAccessOrgIsFalseWhenNotAMember() {
        when(currentUserAccess.get()).thenReturn(UserAccess.none("user-uuid-1"));
        assertFalse(helper.canUserAccessOrg("org1"));
    }
}
