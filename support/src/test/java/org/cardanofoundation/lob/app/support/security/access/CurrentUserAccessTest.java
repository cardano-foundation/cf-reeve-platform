package org.cardanofoundation.lob.app.support.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
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
                "org-a", new OrganisationAccess("org-a", Set.of("org_admin"), Set.of(), Set.of("reports:publish", "reports:view")),
                "org-b", new OrganisationAccess("org-b", Set.of("reports_viewer"), Set.of(), Set.of("reports:view")))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void permissionIsCheckedInTheGivenOrganisation() {
        // manager in org-a, viewer in org-b: the manager rights stay in org-a
        assertThat(permissions.can("org-a", "reports", "publish")).isTrue();
        assertThat(permissions.can("org-b", "reports", "view")).isTrue();
        assertThat(permissions.can("org-b", "reports", "publish")).isFalse();
    }

    @Test
    void organisationTheUserIsNotIn_hasNoPermission() {
        assertThat(permissions.can("org-c", "reports", "view")).isFalse();
    }

    @Test
    void missingOrBlankOrganisation_isRefused() {
        assertThat(permissions.can(null, "reports", "view")).isFalse();
        assertThat(permissions.can("", "reports", "view")).isFalse();
        assertThat(permissions.can("  ", "reports", "view")).isFalse();
    }

    @Test
    void missingOrganisation_isRefusedEvenForAUserWithASingleOrganisation() {
        when(resolver.resolve(any())).thenReturn(new UserAccess("single", Map.of(
                "org-a", new OrganisationAccess("org-a", Set.of("reports_viewer"), Set.of(), Set.of("reports:view")))));

        assertThat(permissions.can(null, "reports", "view")).isFalse();
    }

    @Test
    void headerIsIgnored() {
        request.addHeader("X-Organisation-Id", "org-a");

        assertThat(permissions.can("org-b", "reports", "publish")).isFalse();
        assertThat(keycloakSecurityHelper.canUserAccessOrg("org-b")).isTrue();
    }

    @Test
    void accessToAnOrganisationIsMembership() {
        assertThat(keycloakSecurityHelper.canUserAccessOrg("org-a")).isTrue();
        assertThat(keycloakSecurityHelper.canUserAccessOrg("org-b")).isTrue();
        assertThat(keycloakSecurityHelper.canUserAccessOrg("org-c")).isFalse();
        assertThat(keycloakSecurityHelper.canUserAccessOrg(null)).isFalse();
    }

    @Test
    void platformPermissionDoesNotNeedAnOrganisation() {
        when(resolver.resolve(any())).thenReturn(new UserAccess("platform.admin", Map.of(), Set.of("organisations:create")));

        assertThat(permissions.canPlatform("organisations", "create")).isTrue();
        assertThat(permissions.can("org-a", "organisations", "create")).isFalse();
    }

    @Test
    void organisationPermissionsDoNotGivePlatformPermissions() {
        assertThat(permissions.canPlatform("organisations", "create")).isFalse();
    }

    @Test
    void thereIsNoPermissionCheckWithoutAnOrganisation() {
        // an endpoint left on the old expression must fail, never grant access
        assertThat(java.util.Arrays.stream(PermissionChecker.class.getMethods())
                .filter(method -> method.getName().equals("can"))
                .map(Method::getParameterCount))
                .containsExactly(3);
    }

    @Test
    void accessIsResolvedOncePerRequest() {
        currentUserAccess.get();
        currentUserAccess.get();
        permissions.can("org-a", "reports", "view");

        verify(resolver, times(1)).resolve(any());
    }

    @Test
    void anonymous_hasNoAccess() {
        SecurityContextHolder.clearContext();

        assertThat(currentUserAccess.get().organisations()).isEmpty();
        assertThat(keycloakSecurityHelper.canUserAccessOrg("org-a")).isFalse();
    }

    @Test
    void evictForgetsTheResultOfThisRequestAndTheResolversCache() {
        currentUserAccess.get();

        currentUserAccess.evict();
        currentUserAccess.get();

        verify(resolver).evict(any());
        verify(resolver, times(2)).resolve(any());
    }

    @Test
    void outsideARequestTheResolverIsAskedEachTime() {
        RequestContextHolder.resetRequestAttributes();

        currentUserAccess.get();
        currentUserAccess.get();

        verify(resolver, times(2)).resolve(any());
    }

    @Test
    void withoutATokenThereIsNoAccess() {
        SecurityContextHolder.clearContext();

        assertThat(currentUserAccess.get().organisations()).isEmpty();
        verify(resolver, times(0)).resolve(any());
    }

    @Test
    void resolversWithoutACacheHaveNothingToEvict() {
        UserAccessResolver withoutCache = jwt -> UserAccess.none("user");

        assertThatCode(() -> withoutCache.evict(TestJwts.jwt(Map.of("sub", "user")))).doesNotThrowAnyException();
    }

}
