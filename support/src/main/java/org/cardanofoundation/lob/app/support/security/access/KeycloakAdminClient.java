package org.cardanofoundation.lob.app.support.security.access;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * Thin client for the parts of the Keycloak Admin REST API we need. Authenticates with the
 * client-credentials grant of the configured service-account client.
 */
@Component
@Slf4j
public class KeycloakAdminClient {

    /**
     * Attribute of an organisation group holding the organisation id. The group name is only a readable label.
     */
    public static final String ORGANISATION_ID_ATTRIBUTE = "organisationId";

    private static final String ATTRIBUTES = "attributes";
    private static final int MAX_RESULTS = 10_000;
    private static final String CHILDREN_WITH_ATTRIBUTES = "/groups/{groupId}/children?briefRepresentation=false&first=0&max=" + MAX_RESULTS;

    public record GroupRef(String id, String name, String path) {
    }

    /**
     * A group somewhere under the organisations group.
     */
    public record GroupNode(String id, String path, Map<String, List<String>> attributes) {

        public String name() {
            return path.substring(path.lastIndexOf('/') + 1);
        }

        public Optional<String> organisationId() {
            return attributes.getOrDefault(ORGANISATION_ID_ATTRIBUTE, List.of()).stream()
                    .filter(value -> !value.isBlank())
                    .findFirst();
        }

    }

    private record AccessToken(String value, Instant expiresAt) {
    }

    private final KeycloakAdminProperties properties;
    private final RestClient restClient;
    private final AtomicReference<AccessToken> accessToken = new AtomicReference<>();
    private volatile String permissionsClientUuid;

    public KeycloakAdminClient(KeycloakAdminProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().baseUrl(properties.getUrl()).build();
    }

    /**
     * Forgets the access token and the permissions client id, e.g. after the realm was re-imported or the client
     * recreated: both are looked up again on the next call.
     */
    public synchronized void forgetCachedState() {
        accessToken.set(null);
        permissionsClientUuid = null;
    }

    public Optional<String> findUserId(String username) {
        JsonNode users = get("/users?username={username}&exact=true&briefRepresentation=true", username);
        for (JsonNode user : users) {
            if (username.equalsIgnoreCase(user.path("username").asText())) {
                return Optional.of(user.path("id").asText());
            }
        }
        return Optional.empty();
    }

    public List<GroupRef> userGroups(String userId) {
        List<GroupRef> groups = new ArrayList<>();
        get("/users/{userId}/groups?briefRepresentation=true&first=0&max=" + MAX_RESULTS, userId)
                .forEach(group -> groups.add(new GroupRef(group.path("id").asText(), group.path("name").asText(), group.path("path").asText())));
        return groups;
    }

    /**
     * Realm roles assigned directly to the group.
     */
    public Set<String> groupRealmRoles(String groupId) {
        return names(get("/groups/{groupId}/role-mappings/realm", groupId));
    }

    /**
     * Permissions (client roles of the permissions client) assigned directly to the group.
     */
    public Set<String> groupPermissions(String groupId) {
        return names(get("/groups/{groupId}/role-mappings/clients/{clientUuid}", groupId, permissionsClientUuid()));
    }

    /**
     * For every realm role, the permissions it grants, following nested composite roles.
     */
    public Map<String, Set<String>> realmRolePermissions() {
        String clientUuid = permissionsClientUuid();
        Map<String, List<JsonNode>> compositesByRole = new LinkedHashMap<>();
        for (JsonNode role : get("/roles?briefRepresentation=true&first=0&max=" + MAX_RESULTS)) {
            String name = role.path("name").asText();
            List<JsonNode> composites = new ArrayList<>();
            if (role.path("composite").asBoolean(false)) {
                get("/roles/{roleName}/composites", name).forEach(composites::add);
            }
            compositesByRole.put(name, composites);
        }

        Map<String, Set<String>> permissionsByRole = new LinkedHashMap<>();
        for (String role : compositesByRole.keySet()) {
            permissionsByRole.put(role, expand(role, compositesByRole, clientUuid, new LinkedHashSet<>()));
        }
        return permissionsByRole;
    }

    public Optional<String> findGroupIdByPath(String path) {
        try {
            return Optional.of(get("/group-by-path/{path}", path).path("id").asText());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /**
     * Every group under the organisations group (organisations, module folders and role groups), with attributes.
     */
    public List<GroupNode> organisationTree() {
        Optional<String> parentId = findGroupIdByPath(properties.getOrganisationsGroup());
        if (parentId.isEmpty()) {
            return List.of();
        }
        List<GroupNode> nodes = new ArrayList<>();
        collectChildren(parentId.get(), nodes);
        return nodes;
    }

    /**
     * The organisation groups only (direct children of the organisations group), with attributes.
     */
    public List<GroupNode> organisationGroups() {
        Optional<String> parentId = findGroupIdByPath(properties.getOrganisationsGroup());
        if (parentId.isEmpty()) {
            return List.of();
        }
        List<GroupNode> nodes = new ArrayList<>();
        get(CHILDREN_WITH_ATTRIBUTES, parentId.get()).forEach(group -> nodes.add(groupNode(group)));
        return nodes;
    }

    private void collectChildren(String groupId, List<GroupNode> nodes) {
        for (JsonNode group : get(CHILDREN_WITH_ATTRIBUTES, groupId)) {
            GroupNode node = groupNode(group);
            nodes.add(node);
            if (group.path("subGroupCount").asInt(1) > 0) {
                collectChildren(node.id(), nodes);
            }
        }
    }

    private static GroupNode groupNode(JsonNode group) {
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        group.path(ATTRIBUTES).properties().forEach(attribute -> {
            List<String> values = new ArrayList<>();
            attribute.getValue().forEach(value -> values.add(value.asText()));
            attributes.put(attribute.getKey(), values);
        });
        return new GroupNode(group.path("id").asText(), group.path("path").asText(), attributes);
    }

    public List<GroupRef> childGroups(String groupId) {
        List<GroupRef> groups = new ArrayList<>();
        get("/groups/{groupId}/children?briefRepresentation=true&first=0&max=" + MAX_RESULTS, groupId)
                .forEach(group -> groups.add(new GroupRef(group.path("id").asText(), group.path("name").asText(), group.path("path").asText())));
        return groups;
    }

    public String createTopLevelGroup(String name) {
        URI location = restClient.post()
                .uri(adminPath("/groups"))
                .headers(headers -> headers.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name))
                .retrieve()
                .toBodilessEntity()
                .getHeaders()
                .getLocation();
        return idFromLocation(location).orElseGet(() -> findGroupIdByPath(name).orElseThrow());
    }

    public String createChildGroup(String parentId, String name, Map<String, List<String>> attributes) {
        URI location = restClient.post()
                .uri(adminPath("/groups/{groupId}/children"), parentId)
                .headers(headers -> headers.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name, ATTRIBUTES, attributes))
                .retrieve()
                .toBodilessEntity()
                .getHeaders()
                .getLocation();
        return idFromLocation(location).orElseGet(() -> childGroups(parentId).stream()
                .filter(group -> group.name().equals(name))
                .map(GroupRef::id)
                .findFirst()
                .orElseThrow());
    }

    public void renameGroup(GroupNode group, String name) {
        restClient.put()
                .uri(adminPath("/groups/{groupId}"), group.id())
                .headers(headers -> headers.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name, ATTRIBUTES, group.attributes()))
                .retrieve()
                .toBodilessEntity();
    }

    public void assignRealmRoleToGroup(String groupId, String roleName) {
        JsonNode role = get("/roles/{roleName}", roleName);
        restClient.post()
                .uri(adminPath("/groups/{groupId}/role-mappings/realm"), groupId)
                .headers(headers -> headers.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(Map.of("id", role.path("id").asText(), "name", roleName)))
                .retrieve()
                .toBodilessEntity();
    }

    public void addUserToGroup(String userId, String groupId) {
        restClient.put()
                .uri(adminPath("/users/{userId}/groups/{groupId}"), userId, groupId)
                .headers(headers -> headers.setBearerAuth(token()))
                .retrieve()
                .toBodilessEntity();
    }

    private Set<String> expand(String role, Map<String, List<JsonNode>> compositesByRole, String clientUuid, Set<String> visited) {
        Set<String> permissions = new LinkedHashSet<>();
        if (!visited.add(role)) {
            return permissions;
        }
        for (JsonNode composite : compositesByRole.getOrDefault(role, List.of())) {
            String name = composite.path("name").asText();
            if (composite.path("clientRole").asBoolean(false)) {
                if (clientUuid.equals(composite.path("containerId").asText())) {
                    permissions.add(name);
                }
            } else {
                permissions.addAll(expand(name, compositesByRole, clientUuid, visited));
            }
        }
        return permissions;
    }

    private String permissionsClientUuid() {
        if (permissionsClientUuid == null) {
            JsonNode clients = get("/clients?clientId={clientId}", properties.getPermissionsClientId());
            if (!clients.isArray() || clients.isEmpty()) {
                throw new IllegalStateException("Keycloak client '%s' holding the permissions does not exist".formatted(properties.getPermissionsClientId()));
            }
            permissionsClientUuid = clients.get(0).path("id").asText();
        }
        return permissionsClientUuid;
    }

    private JsonNode get(String path, Object... uriVariables) {
        JsonNode body = restClient.get()
                .uri(adminPath(path), uriVariables)
                .headers(headers -> headers.setBearerAuth(token()))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        return body == null ? MissingNode.getInstance() : body;
    }

    private String adminPath(String path) {
        return "/admin/realms/" + properties.getRealm() + path;
    }

    private synchronized String token() {
        AccessToken current = accessToken.get();
        if (current != null && Instant.now().isBefore(current.expiresAt())) {
            return current.value();
        }
        if (properties.getClientSecret() == null || properties.getClientSecret().isBlank()) {
            throw new IllegalStateException("lob.keycloak.admin.client-secret (LOB_KEYCLOAK_ADMIN_CLIENT_SECRET) is not set: "
                    + "the backend can't read organisation access from Keycloak");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());

        JsonNode response = restClient.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", properties.getRealm())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || !response.hasNonNull("access_token")) {
            throw new IllegalStateException("Keycloak did not return an access token for client " + properties.getClientId());
        }
        long expiresIn = response.path("expires_in").asLong(60);
        AccessToken fresh = new AccessToken(response.get("access_token").asText(), Instant.now().plusSeconds(Math.max(expiresIn - 15, 5)));
        accessToken.set(fresh);
        return fresh.value();
    }

    private static Set<String> names(JsonNode roles) {
        Set<String> names = new LinkedHashSet<>();
        roles.forEach(role -> names.add(role.path("name").asText()));
        return names;
    }

    private static Optional<String> idFromLocation(URI location) {
        if (location == null) {
            return Optional.empty();
        }
        String path = location.getPath();
        return Optional.of(path.substring(path.lastIndexOf('/') + 1));
    }

}
