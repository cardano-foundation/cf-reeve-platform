package org.cardanofoundation.lob.app.support.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.security.oauth2.jwt.Jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.support.security.access.KeycloakAdminClient.GroupNode;
import org.cardanofoundation.lob.app.support.security.access.KeycloakAdminClient.GroupRef;

class KeycloakUserAccessResolverTest {

    private final KeycloakAdminClient client = mock(KeycloakAdminClient.class);
    private final PermissionCatalogue catalogue = new PermissionCatalogue();
    private KeycloakUserAccessResolver resolver;

    @BeforeEach
    void setUp() {
        KeycloakAdminProperties properties = new KeycloakAdminProperties("http://kc", "reeve-master", "reeve-backend", "secret",
                "reeve-api", "organisations", Duration.ofSeconds(30));
        resolver = new KeycloakUserAccessResolver(client, properties, catalogue);

        when(client.realmRolePermissions()).thenReturn(Map.of(
                "org_member", Set.of("settings:view", "organization:view"),
                "transactions_reviewer", Set.of("transactions:review_view", "transactions:review_approve"),
                "reports_viewer", Set.of("reports:view", "chart_of_accounts:view"),
                "funding_publisher", Set.of("project_management:publish"),
                "offline_access", Set.of()));
        when(client.organisationTree()).thenReturn(List.of(
                new GroupNode("g-a", "/organisations/org-a", Map.of("organisationId", List.of("org-a"), "modules", List.of("transactions"))),
                new GroupNode("g-a-tx", "/organisations/org-a/transactions", Map.of()),
                new GroupNode("g-a-tx-rev", "/organisations/org-a/transactions/reviewer", Map.of()),
                new GroupNode("g-b", "/organisations/org-b", Map.of("organisationId", List.of("org-b"))),
                new GroupNode("g-b-rep", "/organisations/org-b/reporting", Map.of()),
                new GroupNode("g-b-rep-view", "/organisations/org-b/reporting/viewer", Map.of())));
        when(client.groupRealmRoles(anyString())).thenReturn(Set.of());
        when(client.groupPermissions(anyString())).thenReturn(Set.of());
        when(client.groupRealmRoles("g-a")).thenReturn(Set.of("org_member"));
        when(client.groupRealmRoles("g-b")).thenReturn(Set.of("org_member"));
        when(client.groupRealmRoles("g-a-tx-rev")).thenReturn(Set.of("transactions_reviewer"));
        when(client.groupRealmRoles("g-b-rep-view")).thenReturn(Set.of("reports_viewer", "offline_access"));
    }

    @Test
    void moduleSubgroupGrantsItsRoleInThatOrganisationOnly_andInheritsTheOrganisationRole() {
        when(client.userGroups("user-1")).thenReturn(List.of(
                new GroupRef("g-a-tx-rev", "reviewer", "/organisations/org-a/transactions/reviewer"),
                new GroupRef("g-b-rep-view", "viewer", "/organisations/org-b/reporting/viewer")));

        UserAccess access = resolver.resolve(TestJwts.jwt(Map.of("sub", "user-1", "preferred_username", "multi")));

        OrganisationAccess orgA = access.organisation("org-a").orElseThrow();
        assertThat(orgA.roles()).containsExactlyInAnyOrder("org_member", "transactions_reviewer");
        assertThat(orgA.permissions()).containsExactlyInAnyOrder("settings:view", "organization:view",
                "transactions:review_view", "transactions:review_approve");

        OrganisationAccess orgB = access.organisation("org-b").orElseThrow();
        assertThat(orgB.roles()).containsExactlyInAnyOrder("org_member", "reports_viewer");
        assertThat(orgB.hasPermission("reports", "view")).isTrue();
        assertThat(orgB.hasPermission("transactions", "review_approve")).isFalse();
    }

    @Test
    void deepSubgroupInheritsEveryParentRole_butNotItsSiblings() {
        when(client.organisationTree()).thenReturn(List.of(
                new GroupNode("g-c", "/organisations/org-c", Map.of("organisationId", List.of("org-c"))),
                new GroupNode("g-c-tx", "/organisations/org-c/transactions", Map.of()),
                new GroupNode("g-c-tx-setup", "/organisations/org-c/transactions/setup", Map.of()),
                new GroupNode("g-c-tx-setup-erp", "/organisations/org-c/transactions/setup/erp", Map.of()),
                new GroupNode("g-c-tx-pub", "/organisations/org-c/transactions/publisher", Map.of())));
        when(client.groupRealmRoles("g-c")).thenReturn(Set.of("org_member"));
        when(client.groupRealmRoles("g-c-tx")).thenReturn(Set.of("transactions_viewer"));
        when(client.groupRealmRoles("g-c-tx-setup")).thenReturn(Set.of("transactions_setup"));
        when(client.groupRealmRoles("g-c-tx-setup-erp")).thenReturn(Set.of("transactions_erp"));
        when(client.groupRealmRoles("g-c-tx-pub")).thenReturn(Set.of("transactions_publisher"));
        when(client.realmRolePermissions()).thenReturn(Map.of(
                "org_member", Set.of("settings:view"),
                "transactions_viewer", Set.of("transactions:view"),
                "transactions_setup", Set.of("chart_of_accounts:edit"),
                "transactions_erp", Set.of("netsuite_configuration:edit"),
                "transactions_publisher", Set.of("transactions:publish_approve")));
        when(client.userGroups("user-9")).thenReturn(List.of(
                new GroupRef("g-c-tx-setup-erp", "erp", "/organisations/org-c/transactions/setup/erp")));

        OrganisationAccess orgC = resolver.resolve(TestJwts.jwt(Map.of("sub", "user-9", "preferred_username", "u9")))
                .organisation("org-c").orElseThrow();

        assertThat(orgC.roles()).containsExactlyInAnyOrder("org_member", "transactions_viewer", "transactions_setup", "transactions_erp");
        // publisher is a sibling, not a parent: not inherited (four-eyes)
        assertThat(orgC.hasPermission("transactions", "publish_approve")).isFalse();
    }

    @Test
    void sharedResourceIsAvailableWhenAnyOfItsModulesIsEnabled() {
        // chart_of_accounts belongs to transactions and reporting; org-a only has transactions
        when(client.userGroups("user-2")).thenReturn(List.of(new GroupRef("g-a-custom", "custom", "/organisations/org-a/custom")));
        when(client.groupRealmRoles("g-a-custom")).thenReturn(Set.of("reports_viewer"));

        OrganisationAccess orgA = resolver.resolve(TestJwts.jwt(Map.of("sub", "user-2", "preferred_username", "u2")))
                .organisation("org-a").orElseThrow();

        assertThat(orgA.permissions()).contains("chart_of_accounts:view").doesNotContain("reports:view");
    }

    @Test
    void directMemberOfTheOrganisationGroupOnlyGetsTheOrganisationRole() {
        when(client.userGroups("user-3")).thenReturn(List.of(new GroupRef("g-b", "org-b", "/organisations/org-b")));

        OrganisationAccess orgB = resolver.resolve(TestJwts.jwt(Map.of("sub", "user-3", "preferred_username", "u3")))
                .organisation("org-b").orElseThrow();

        assertThat(orgB.roles()).containsExactly("org_member");
        assertThat(orgB.permissions()).containsExactlyInAnyOrder("settings:view", "organization:view");
    }

    @Test
    void theOrganisationIdComesFromTheAttribute_theGroupNameIsOnlyALabel() {
        when(client.organisationTree()).thenReturn(List.of(
                new GroupNode("g-acme", "/organisations/Acme Ltd", Map.of("organisationId", List.of("org-acme"), "modules", List.of("funding"))),
                new GroupNode("g-acme-fund", "/organisations/Acme Ltd/funding", Map.of()),
                new GroupNode("g-acme-pub", "/organisations/Acme Ltd/funding/publisher", Map.of())));
        when(client.groupRealmRoles("g-acme-pub")).thenReturn(Set.of("funding_publisher"));
        when(client.userGroups("user-13")).thenReturn(List.of(new GroupRef("g-acme-pub", "publisher", "/organisations/Acme Ltd/funding/publisher")));

        UserAccess access = resolver.resolve(TestJwts.jwt(Map.of("sub", "user-13", "preferred_username", "u13")));

        assertThat(access.isMemberOf("Acme Ltd")).isFalse();
        OrganisationAccess acme = access.organisation("org-acme").orElseThrow();
        assertThat(acme.modules()).containsExactly("funding");
        assertThat(acme.hasPermission("project_management", "publish")).isTrue();
    }

    @Test
    void groupWithoutOrganisationIdGivesNoAccess() {
        when(client.organisationTree()).thenReturn(List.of(new GroupNode("g-x", "/organisations/org-x", Map.of())));
        when(client.groupRealmRoles("g-x")).thenReturn(Set.of("org_member"));
        when(client.userGroups("user-14")).thenReturn(List.of(new GroupRef("g-x", "org-x", "/organisations/org-x")));

        assertThat(resolver.resolve(TestJwts.jwt(Map.of("sub", "user-14", "preferred_username", "u14"))).organisations()).isEmpty();
    }

    @Test
    void organisationCreatedAfterTheTreeWasCachedIsFoundStraightAway() {
        when(client.userGroups("user-15")).thenReturn(List.of(new GroupRef("g-a", "org-a", "/organisations/org-a")));
        resolver.resolve(TestJwts.jwt(Map.of("sub", "user-15", "preferred_username", "u15")));

        when(client.organisationTree()).thenReturn(List.of(new GroupNode("g-new", "/organisations/New Org", Map.of("organisationId", List.of("org-new")))));
        when(client.userGroups("user-16")).thenReturn(List.of(new GroupRef("g-new", "New Org", "/organisations/New Org")));

        assertThat(resolver.resolve(TestJwts.jwt(Map.of("sub", "user-16", "preferred_username", "u16"))).isMemberOf("org-new")).isTrue();
    }

    @Test
    void legacyOrganisationsClaimIsIgnored() {
        when(client.userGroups("user-4")).thenReturn(List.of());

        UserAccess access = resolver.resolve(TestJwts.jwt(Map.of(
                "sub", "user-4",
                "preferred_username", "legacy",
                "organisations", List.of("org-a"),
                "roles", List.of("reeve_account_manager"))));

        assertThat(access.organisations()).isEmpty();
    }

    @Test
    void permissionsAssignedDirectlyToAGroupAreAdded() {
        when(client.userGroups("user-5")).thenReturn(List.of(new GroupRef("g-b-custom", "custom", "/organisations/org-b/custom")));
        when(client.groupPermissions("g-b-custom")).thenReturn(Set.of("project_management:publish"));

        OrganisationAccess orgB = resolver.resolve(TestJwts.jwt(Map.of("sub", "user-5", "preferred_username", "u5")))
                .organisation("org-b").orElseThrow();

        assertThat(orgB.permissions()).contains("project_management:publish");
    }

    @Test
    void groupsOutsideTheOrganisationsTreeAreIgnored() {
        when(client.userGroups("user-6")).thenReturn(List.of(new GroupRef("x", "other", "/something-else/org-a")));

        assertThat(resolver.resolve(TestJwts.jwt(Map.of("sub", "user-6", "preferred_username", "u6"))).organisations()).isEmpty();
    }

    @Test
    void looksUpTheUserByUsernameWhenTheTokenHasNoSubject_andCaches() {
        when(client.findUserId("manager")).thenReturn(Optional.of("user-7"));
        when(client.userGroups("user-7")).thenReturn(List.of(new GroupRef("g-a-tx-rev", "reviewer", "/organisations/org-a/transactions/reviewer")));

        UserAccess access = resolver.resolve(TestJwts.jwt(Map.of("preferred_username", "manager")));
        resolver.resolve(TestJwts.jwt(Map.of("preferred_username", "manager")));

        assertThat(access.isMemberOf("org-a")).isTrue();
        verify(client, times(1)).findUserId("manager");
        verify(client, times(1)).userGroups("user-7");
    }

    @Test
    void platformGroupGivesPlatformPermissionsButNoOrganisation() {
        when(client.realmRolePermissions()).thenReturn(Map.of("platform_admin", Set.of("organisations:create")));
        when(client.userGroups("user-11")).thenReturn(List.of(new GroupRef("g-p-admins", "administrators", "/platform/administrators")));
        when(client.groupRealmRoles("g-p-admins")).thenReturn(Set.of("platform_admin"));

        UserAccess access = resolver.resolve(TestJwts.jwt(Map.of("sub", "user-11", "preferred_username", "platform.admin")));

        assertThat(access.hasPlatformPermission("organisations", "create")).isTrue();
        assertThat(access.organisations()).isEmpty();
    }

    @Test
    void organisationRolesNeverGivePlatformPermissions() {
        when(client.realmRolePermissions()).thenReturn(Map.of("org_member", Set.of("settings:view"), "platform_admin", Set.of("organisations:create")));
        when(client.userGroups("user-12")).thenReturn(List.of(new GroupRef("g-a", "org-a", "/organisations/org-a")));
        when(client.groupRealmRoles("g-a")).thenReturn(Set.of("org_member"));

        UserAccess access = resolver.resolve(TestJwts.jwt(Map.of("sub", "user-12", "preferred_username", "u12")));

        assertThat(access.platformPermissions()).isEmpty();
    }

    @Test
    void keycloakResetIsRecoveredByOneRetryWithFreshState() {
        // first lookup fails (e.g. realm re-imported: stale token / client id / group ids), the retry succeeds
        when(client.userGroups("user-10"))
                .thenThrow(new IllegalStateException("401 from Keycloak"))
                .thenReturn(List.of(new GroupRef("g-a-tx-rev", "reviewer", "/organisations/org-a/transactions/reviewer")));

        UserAccess access = resolver.resolve(TestJwts.jwt(Map.of("sub", "user-10", "preferred_username", "u10")));

        assertThat(access.isMemberOf("org-a")).isTrue();
        verify(client, times(1)).forgetCachedState();
        // the role definitions and organisation tree were reloaded, not taken from the stale cache
        verify(client, times(2)).realmRolePermissions();
        verify(client, times(2)).organisationTree();
    }

    @Test
    void userUnknownToKeycloakGetsNoAccess() {
        when(client.findUserId("ghost")).thenReturn(Optional.empty());

        UserAccess access = resolver.resolve(TestJwts.jwt(Map.of("preferred_username", "ghost")));

        assertThat(access.organisations()).isEmpty();
        verify(client, never()).userGroups(anyString());
    }

    @Test
    void evictMakesTheNextLookupSeeChangesInKeycloak() {
        Jwt jwt = TestJwts.jwt(Map.of("sub", "user-17", "preferred_username", "u17"));
        when(client.userGroups("user-17")).thenReturn(List.of());
        assertThat(resolver.resolve(jwt).organisations()).isEmpty();

        when(client.userGroups("user-17")).thenReturn(List.of(new GroupRef("g-a", "org-a", "/organisations/org-a")));
        assertThat(resolver.resolve(jwt).organisations()).isEmpty();

        resolver.evict(jwt);

        assertThat(resolver.resolve(jwt).isMemberOf("org-a")).isTrue();
    }

    @Test
    void keycloakFailure_grantsNoAccess() {
        when(client.userGroups("user-8")).thenThrow(new IllegalStateException("Keycloak down"));

        assertThat(resolver.resolve(TestJwts.jwt(Map.of("sub", "user-8", "preferred_username", "u8"))).organisations()).isEmpty();
        // retried once with fresh state before giving up
        verify(client, times(2)).userGroups("user-8");
        verify(client, times(1)).forgetCachedState();
    }

}
