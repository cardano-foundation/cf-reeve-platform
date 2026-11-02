package org.cardanofoundation.lob.app.support.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

class CurrentUserAccessTest {

    private final UserAccessResolver resolver = mock(UserAccessResolver.class);
    private final CurrentUserAccess currentUserAccess = new CurrentUserAccess(resolver);
    private final KeycloakSecurityHelper keycloakSecurityHelper = new KeycloakSecurityHelper(currentUserAccess);
    private final PermissionChecker permissions = new PermissionChecker(currentUserAccess);

    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        Jwt jwt = TestJwts.jwt(Map.of("preferred_username", "multi"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        when(resolver.resolve(any())).thenReturn(new UserAccess("multi", Map.of(
                "org-a", new OrganisationAccess("org-a", Set.of("reeve_account_manager"), Set.of(), Set.of("reports:publish", "reports:view")),
                "org-b", new OrganisationAccess("org-b", Set.of("reeve_auditor"), Set.of(), Set.of("reports:view")))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void permissionIsCheckedInTheRequestedOrganisation() {
        request.addHeader(CurrentUserAccess.ORGANISATION_HEADER, "org-b");

        assertThat(permissions.can("reports", "view")).isTrue();
        assertThat(permissions.can("reports", "publish")).isFalse();
    }

    @Test
    void organisationOfTheRequestMustMatchTheHeader() {
        request.addHeader(CurrentUserAccess.ORGANISATION_HEADER, "org-b");

        assertThat(keycloakSecurityHelper.canUserAccessOrg("org-b")).isTrue();
        // member of org-a, but the request acts on org-b: a manager permission checked for org-b must not touch org-a data
        assertThat(keycloakSecurityHelper.canUserAccessOrg("org-a")).isFalse();
        assertThat(keycloakSecurityHelper.isMemberOfOrg("org-a")).isTrue();
        assertThat(keycloakSecurityHelper.canUserAccessOrg("org-c")).isFalse();
        assertThat(keycloakSecurityHelper.canUserAccessOrg(null)).isFalse();
    }

    @Test
    void withoutHeader_permissionNeedsASingleOrganisation() {
        assertThat(keycloakSecurityHelper.canUserAccessOrg("org-a")).isTrue();
        // two organisations and no header: ambiguous, deny
        assertThat(permissions.can("reports", "view")).isFalse();
    }

    @Test
    void withoutHeader_singleOrganisationIsUsed() {
        when(resolver.resolve(any())).thenReturn(new UserAccess("single", Map.of(
                "org-a", new OrganisationAccess("org-a", Set.of("reeve_auditor"), Set.of(), Set.of("reports:view")))));

        assertThat(permissions.can("reports", "view")).isTrue();
    }

    @Test
    void headerForAnOrganisationTheUserIsNotIn_deniesEverything() {
        request.addHeader(CurrentUserAccess.ORGANISATION_HEADER, "org-c");

        assertThat(permissions.can("reports", "view")).isFalse();
        assertThat(keycloakSecurityHelper.canUserAccessOrg("org-a")).isFalse();
    }

    @Test
    void platformPermissionDoesNotNeedAnOrganisation() {
        when(resolver.resolve(any())).thenReturn(new UserAccess("platform.admin", Map.of(), Set.of("organisations:create")));

        assertThat(permissions.canPlatform("organisations", "create")).isTrue();
        assertThat(permissions.can("organisations", "create")).isFalse();
    }

    @Test
    void organisationPermissionsDoNotGivePlatformPermissions() {
        request.addHeader(CurrentUserAccess.ORGANISATION_HEADER, "org-a");

        assertThat(permissions.canPlatform("organisations", "create")).isFalse();
    }

    @Test
    void accessIsResolvedOncePerRequest() {
        currentUserAccess.get();
        currentUserAccess.get();
        permissions.can("reports", "view");

        verify(resolver, times(1)).resolve(any());
    }

    @Test
    void anonymous_hasNoAccess() {
        SecurityContextHolder.clearContext();

        assertThat(currentUserAccess.get().organisations()).isEmpty();
        assertThat(keycloakSecurityHelper.canUserAccessOrg("org-a")).isFalse();
    }

}
