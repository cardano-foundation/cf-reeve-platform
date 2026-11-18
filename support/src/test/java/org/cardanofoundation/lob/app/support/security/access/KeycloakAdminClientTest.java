package org.cardanofoundation.lob.app.support.security.access;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.client.HttpClientErrorException;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import org.cardanofoundation.lob.app.support.security.access.KeycloakAdminClient.GroupNode;
import org.cardanofoundation.lob.app.support.security.access.KeycloakAdminClient.GroupRef;

/**
 * Runs the client against an HTTP server, so the requests it really sends to Keycloak are checked.
 */
class KeycloakAdminClientTest {

    private static final String TOKEN_PATH = "/realms/reeve-master/protocol/openid-connect/token";
    private static final String ADMIN = "/admin/realms/reeve-master";

    @RegisterExtension
    static final WireMockExtension KEYCLOAK = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort().http2PlainDisabled(true)).build();

    private KeycloakAdminClient client;

    @BeforeEach
    void setUp() {
        client = new KeycloakAdminClient(properties("secret"));
        KEYCLOAK.stubFor(post(TOKEN_PATH).willReturn(okJson("""
                {"access_token": "token-1", "expires_in": 300}""")));
    }

    private static KeycloakAdminProperties properties(String secret) {
        return new KeycloakAdminProperties(KEYCLOAK.baseUrl(), "reeve-master", "reeve-backend", secret, "reeve-api", "organisations", Duration.ofSeconds(30));
    }

    private static void stubGet(String path, String json) {
        KEYCLOAK.stubFor(get(urlPathEqualTo(ADMIN + path)).willReturn(okJson(json)));
    }

    private static void stubPermissionsClient() {
        stubGet("/clients", """
                [{"id": "api-uuid", "clientId": "reeve-api"}]""");
    }

    @Test
    void authenticatesWithTheClientCredentialsOfTheServiceAccount() {
        stubGet("/users/u1/groups", "[]");

        client.userGroups("u1");

        KEYCLOAK.verify(postRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withRequestBody(equalTo("grant_type=client_credentials&client_id=reeve-backend&client_secret=secret")));
        KEYCLOAK.verify(getRequestedFor(urlPathEqualTo(ADMIN + "/users/u1/groups")).withHeader("Authorization", equalTo("Bearer token-1")));
    }

    @Test
    void reusesTheTokenUntilTheStateIsForgotten() {
        stubGet("/users/u1/groups", "[]");

        client.userGroups("u1");
        client.userGroups("u1");
        KEYCLOAK.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));

        client.forgetCachedState();
        client.userGroups("u1");
        KEYCLOAK.verify(2, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    @Test
    void missingSecretIsReportedWithoutCallingKeycloak() {
        KeycloakAdminClient withoutSecret = new KeycloakAdminClient(properties(" "));

        assertThatThrownBy(() -> withoutSecret.userGroups("u1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOB_KEYCLOAK_ADMIN_CLIENT_SECRET");
        KEYCLOAK.verify(0, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    @Test
    void tokenResponseWithoutAccessTokenIsAnError() {
        KEYCLOAK.stubFor(post(TOKEN_PATH).willReturn(okJson("{}")));

        assertThatThrownBy(() -> client.userGroups("u1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reeve-backend");
    }

    @Test
    void findsAUserByExactUsername_ignoringCase() {
        stubGet("/users", """
                [{"id": "other", "username": "manager2"}, {"id": "u1", "username": "Manager"}]""");

        assertThat(client.findUserId("manager")).contains("u1");
    }

    @Test
    void unknownUserIsEmpty() {
        stubGet("/users", "[]");

        assertThat(client.findUserId("nobody")).isEmpty();
    }

    @Test
    void readsTheGroupsOfAUser() {
        stubGet("/users/u1/groups", """
                [{"id": "g1", "name": "reviewer", "path": "/organisations/Acme/transactions/reviewer"}]""");

        assertThat(client.userGroups("u1")).containsExactly(new GroupRef("g1", "reviewer", "/organisations/Acme/transactions/reviewer"));
    }

    @Test
    void readsTheRolesAndPermissionsOfAGroup() {
        stubPermissionsClient();
        stubGet("/groups/g1/role-mappings/realm", """
                [{"name": "transactions_reviewer"}, {"name": "org_member"}]""");
        stubGet("/groups/g1/role-mappings/clients/api-uuid", """
                [{"name": "reports:view"}]""");

        assertThat(client.groupRealmRoles("g1")).containsExactlyInAnyOrder("transactions_reviewer", "org_member");
        assertThat(client.groupPermissions("g1")).containsExactly("reports:view");
    }

    @Test
    void looksUpThePermissionsClientOnlyOnce_untilTheStateIsForgotten() {
        stubPermissionsClient();
        stubGet("/groups/g1/role-mappings/clients/api-uuid", "[]");

        client.groupPermissions("g1");
        client.groupPermissions("g1");
        KEYCLOAK.verify(1, getRequestedFor(urlPathEqualTo(ADMIN + "/clients")));

        client.forgetCachedState();
        client.groupPermissions("g1");
        KEYCLOAK.verify(2, getRequestedFor(urlPathEqualTo(ADMIN + "/clients")));
    }

    @Test
    void missingPermissionsClientIsAnError() {
        stubGet("/clients", "[]");

        assertThatThrownBy(() -> client.groupPermissions("g1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reeve-api");
    }

    @Test
    void expandsCompositeRolesIntoPermissions_includingNestedOnes() {
        stubPermissionsClient();
        stubGet("/roles", """
                [
                  {"name": "bundle", "composite": true},
                  {"name": "module_role", "composite": true},
                  {"name": "offline_access", "composite": false}
                ]""");
        stubGet("/roles/bundle/composites", """
                [
                  {"name": "module_role", "clientRole": false},
                  {"name": "settings:view", "clientRole": true, "containerId": "api-uuid"}
                ]""");
        stubGet("/roles/module_role/composites", """
                [
                  {"name": "reports:view", "clientRole": true, "containerId": "api-uuid"},
                  {"name": "manage-account", "clientRole": true, "containerId": "another-client"}
                ]""");

        Map<String, Set<String>> permissions = client.realmRolePermissions();

        assertThat(permissions.get("bundle")).containsExactlyInAnyOrder("settings:view", "reports:view");
        assertThat(permissions.get("module_role")).containsExactly("reports:view");
        assertThat(permissions.get("offline_access")).isEmpty();
    }

    @Test
    void compositeRolesReferringToEachOtherDoNotLoopForever() {
        stubPermissionsClient();
        stubGet("/roles", """
                [{"name": "a", "composite": true}, {"name": "b", "composite": true}]""");
        stubGet("/roles/a/composites", """
                [{"name": "b", "clientRole": false}, {"name": "reports:view", "clientRole": true, "containerId": "api-uuid"}]""");
        stubGet("/roles/b/composites", """
                [{"name": "a", "clientRole": false}]""");

        assertThat(client.realmRolePermissions().get("b")).containsExactly("reports:view");
    }

    @Test
    void findsAGroupByPath() {
        stubGet("/group-by-path/organisations", """
                {"id": "root"}""");

        assertThat(client.findGroupIdByPath("organisations")).contains("root");
    }

    @Test
    void unknownGroupPathIsEmpty() {
        KEYCLOAK.stubFor(get(urlPathEqualTo(ADMIN + "/group-by-path/missing")).willReturn(notFound()));

        assertThat(client.findGroupIdByPath("missing")).isEmpty();
    }

    @Test
    void otherErrorsAreNotHidden() {
        KEYCLOAK.stubFor(get(urlPathEqualTo(ADMIN + "/users/u1/groups")).willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> client.userGroups("u1")).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void readsTheWholeOrganisationTreeWithAttributes_withoutAskingForChildrenOfLeafGroups() {
        stubGet("/group-by-path/organisations", """
                {"id": "root"}""");
        stubGet("/groups/root/children", """
                [{"id": "g-acme", "path": "/organisations/Acme Ltd", "subGroupCount": 1,
                  "attributes": {"organisationId": ["org-acme"], "modules": ["transactions", "reporting"]}}]""");
        stubGet("/groups/g-acme/children", """
                [{"id": "g-tx", "path": "/organisations/Acme Ltd/transactions", "subGroupCount": 0}]""");

        List<GroupNode> tree = client.organisationTree();

        assertThat(tree).containsExactly(
                new GroupNode("g-acme", "/organisations/Acme Ltd", Map.of("organisationId", List.of("org-acme"), "modules", List.of("transactions", "reporting"))),
                new GroupNode("g-tx", "/organisations/Acme Ltd/transactions", Map.of()));
        KEYCLOAK.verify(0, getRequestedFor(urlPathEqualTo(ADMIN + "/groups/g-tx/children")));
    }

    @Test
    void organisationGroupsAreOnlyTheDirectChildren() {
        stubGet("/group-by-path/organisations", """
                {"id": "root"}""");
        stubGet("/groups/root/children", """
                [{"id": "g-acme", "path": "/organisations/Acme Ltd", "subGroupCount": 1, "attributes": {"organisationId": ["org-acme"]}}]""");

        List<GroupNode> groups = client.organisationGroups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).name()).isEqualTo("Acme Ltd");
        assertThat(groups.get(0).organisationId()).contains("org-acme");
        KEYCLOAK.verify(0, getRequestedFor(urlPathEqualTo(ADMIN + "/groups/g-acme/children")));
    }

    @Test
    void withoutTheOrganisationsGroupThereAreNoOrganisations() {
        KEYCLOAK.stubFor(get(urlPathEqualTo(ADMIN + "/group-by-path/organisations")).willReturn(notFound()));

        assertThat(client.organisationTree()).isEmpty();
        assertThat(client.organisationGroups()).isEmpty();
    }

    @Test
    void blankOrMissingOrganisationIdAttributeIsEmpty() {
        assertThat(new GroupNode("g", "/organisations/A", Map.of()).organisationId()).isEmpty();
        assertThat(new GroupNode("g", "/organisations/A", Map.of("organisationId", List.of(" "))).organisationId()).isEmpty();
    }

    @Test
    void readsChildGroups() {
        stubGet("/groups/g1/children", """
                [{"id": "g2", "name": "setup", "path": "/organisations/Acme/setup"}]""");

        assertThat(client.childGroups("g1")).containsExactly(new GroupRef("g2", "setup", "/organisations/Acme/setup"));
    }

    @Test
    void createsATopLevelGroup_takingTheIdFromTheLocationHeader() {
        KEYCLOAK.stubFor(post(ADMIN + "/groups").willReturn(aResponse().withStatus(201).withHeader("Location", KEYCLOAK.baseUrl() + ADMIN + "/groups/new-id")));

        assertThat(client.createTopLevelGroup("organisations")).isEqualTo("new-id");
        KEYCLOAK.verify(postRequestedFor(urlPathEqualTo(ADMIN + "/groups")).withRequestBody(equalToJson("""
                {"name": "organisations"}""")));
    }

    @Test
    void createsATopLevelGroup_findingItByPathWithoutLocationHeader() {
        KEYCLOAK.stubFor(post(ADMIN + "/groups").willReturn(aResponse().withStatus(201)));
        stubGet("/group-by-path/organisations", """
                {"id": "found-id"}""");

        assertThat(client.createTopLevelGroup("organisations")).isEqualTo("found-id");
    }

    @Test
    void createsAChildGroupWithItsAttributes() {
        KEYCLOAK.stubFor(post(ADMIN + "/groups/root/children").willReturn(aResponse().withStatus(201).withHeader("Location", KEYCLOAK.baseUrl() + ADMIN + "/groups/new-id")));

        assertThat(client.createChildGroup("root", "Acme Ltd", Map.of("organisationId", List.of("org-acme")))).isEqualTo("new-id");
        KEYCLOAK.verify(postRequestedFor(urlPathEqualTo(ADMIN + "/groups/root/children")).withRequestBody(equalToJson("""
                {"name": "Acme Ltd", "attributes": {"organisationId": ["org-acme"]}}""")));
    }

    @Test
    void createsAChildGroup_findingItByNameWithoutLocationHeader() {
        KEYCLOAK.stubFor(post(ADMIN + "/groups/root/children").willReturn(aResponse().withStatus(201)));
        stubGet("/groups/root/children", """
                [{"id": "other", "name": "Other", "path": "/organisations/Other"}, {"id": "found-id", "name": "Acme", "path": "/organisations/Acme"}]""");

        assertThat(client.createChildGroup("root", "Acme", Map.of())).isEqualTo("found-id");
    }

    @Test
    void renamingAGroupKeepsItsAttributes() {
        KEYCLOAK.stubFor(put(ADMIN + "/groups/g1").willReturn(noContent()));

        client.renameGroup(new GroupNode("g1", "/organisations/Old", Map.of("organisationId", List.of("org-1"), "modules", List.of("funding"))), "New");

        KEYCLOAK.verify(putRequestedFor(urlPathEqualTo(ADMIN + "/groups/g1")).withRequestBody(equalToJson("""
                {"name": "New", "attributes": {"organisationId": ["org-1"], "modules": ["funding"]}}""")));
    }

    @Test
    void mapsARealmRoleOnAGroup() {
        stubGet("/roles/org_member", """
                {"id": "role-id", "name": "org_member"}""");
        KEYCLOAK.stubFor(post(ADMIN + "/groups/g1/role-mappings/realm").willReturn(noContent()));

        client.assignRealmRoleToGroup("g1", "org_member");

        KEYCLOAK.verify(postRequestedFor(urlPathEqualTo(ADMIN + "/groups/g1/role-mappings/realm")).withRequestBody(equalToJson("""
                [{"id": "role-id", "name": "org_member"}]""")));
    }

    @Test
    void addsAUserToAGroup() {
        KEYCLOAK.stubFor(put(ADMIN + "/users/u1/groups/g1").willReturn(noContent()));

        client.addUserToGroup("u1", "g1");

        KEYCLOAK.verify(putRequestedFor(urlPathEqualTo(ADMIN + "/users/u1/groups/g1")));
    }

}
