package org.cardanofoundation.lob.app.support.security.access;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import org.cardanofoundation.lob.app.support.security.access.KeycloakAdminClient.GroupNode;
import org.cardanofoundation.lob.app.support.security.access.KeycloakAdminClient.GroupRef;

/**
 * Keycloak is the source of truth for who can do what in which organisation:
 * <ul>
 *     <li>an organisation is a group {@code /organisations/<organisationId>}; its {@code modules} attribute lists the
 *     enabled modules (missing attribute = every module enabled)</li>
 *     <li>being in any group under it makes the user a member of that organisation</li>
 *     <li>the user gets the realm roles (and permissions) mapped to the groups they are in <b>and to their parent groups
 *     up to the organisation group</b>, so a role mapped on {@code /organisations/<organisationId>} (org_member) applies to
 *     every member, and roles of e.g. {@code /organisations/<organisationId>/transactions/reviewer} only in that organisation</li>
 *     <li>a permission is a client role of the permissions client ({@code reeve-api}), a role is a realm role composed of
 *     permissions and/or other roles</li>
 * </ul>
 * Groups under {@code /platform} (e.g. {@code /platform/administrators}) give platform-wide permissions, such as
 * creating organisations, that are not tied to an organisation.
 * <p>
 * Lookups are cached for {@code lob.keycloak.admin.cache-ttl}, so changes in Keycloak show up without a new login.
 */
@Component
@Slf4j
public class KeycloakUserAccessResolver implements UserAccessResolver {

    private static final String MODULES_ATTRIBUTE = "modules";
    private static final String ALL = "all";

    private final KeycloakAdminClient keycloakAdminClient;
    private final KeycloakAdminProperties properties;
    private final PermissionCatalogue permissionCatalogue;

    private final ExpiringCache<String, Optional<String>> userIds;
    private final ExpiringCache<String, UserAccess> userAccess;
    private final ExpiringCache<String, Set<String>> groupRoles;
    private final ExpiringCache<String, Set<String>> groupPermissions;
    private final ExpiringCache<String, Map<String, Set<String>>> rolePermissions;
    private final ExpiringCache<String, Map<String, GroupNode>> organisationTree;

    public KeycloakUserAccessResolver(KeycloakAdminClient keycloakAdminClient, KeycloakAdminProperties properties, PermissionCatalogue permissionCatalogue) {
        this.keycloakAdminClient = keycloakAdminClient;
        this.properties = properties;
        this.permissionCatalogue = permissionCatalogue;
        this.userIds = new ExpiringCache<>(properties.getCacheTtl().multipliedBy(20));
        this.userAccess = new ExpiringCache<>(properties.getCacheTtl());
        this.groupRoles = new ExpiringCache<>(properties.getCacheTtl());
        this.groupPermissions = new ExpiringCache<>(properties.getCacheTtl());
        this.rolePermissions = new ExpiringCache<>(properties.getCacheTtl());
        this.organisationTree = new ExpiringCache<>(properties.getCacheTtl());
    }

    @Override
    public UserAccess resolve(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        try {
            Optional<String> userId = userId(jwt, username);
            if (userId.isEmpty()) {
                log.warn("User {} not found in Keycloak, no organisation access granted", username);
                return UserAccess.none(username);
            }
            try {
                return userAccess.get(userId.get(), id -> load(id, username));
            } catch (RuntimeException firstFailure) {
                // Keycloak may have been reset or reconfigured (new realm keys, recreated client, new group ids):
                // drop everything remembered about it and try once more before refusing access
                log.warn("Organisation access lookup for user {} failed, retrying with fresh Keycloak state: {}", username, firstFailure.getMessage());
                forgetKeycloakState();
                return userAccess.get(userId.get(), id -> load(id, username));
            }
        } catch (RuntimeException e) {
            log.error("Unable to resolve the organisation access of user {} from Keycloak, no access granted", username, e);
            return UserAccess.none(username);
        }
    }

    private void forgetKeycloakState() {
        keycloakAdminClient.forgetCachedState();
        userIds.clear();
        organisationTree.clear();
        rolePermissions.clear();
        groupRoles.clear();
        groupPermissions.clear();
    }

    @Override
    public void evict(Jwt jwt) {
        userId(jwt, jwt.getClaimAsString("preferred_username")).ifPresent(userAccess::evict);
        organisationTree.clear();
        groupRoles.clear();
        groupPermissions.clear();
    }

    private UserAccess load(String userId, String username) {
        Map<String, Set<String>> permissionsOfRole = rolePermissions.get(ALL, key -> keycloakAdminClient.realmRolePermissions());
        Map<String, GroupNode> groupsByPath = organisationTree.get(ALL, key -> loadOrganisationTree());

        Map<String, Set<String>> rolesByOrganisation = new LinkedHashMap<>();
        Map<String, Set<String>> directPermissionsByOrganisation = new LinkedHashMap<>();

        Set<String> platformPermissions = new LinkedHashSet<>();
        for (GroupRef group : keycloakAdminClient.userGroups(userId)) {
            List<String> segments = pathSegments(group.path());
            if (!segments.isEmpty() && segments.get(0).equals(permissionCatalogue.getPlatformGroup())) {
                // platform-wide access, e.g. /platform/administrators: roles of the group (no organisation, no modules)
                grantingRoles(groupRoles.get(group.id(), keycloakAdminClient::groupRealmRoles), permissionsOfRole)
                        .forEach(role -> platformPermissions.addAll(permissionsOfRole.getOrDefault(role, Set.of())));
                platformPermissions.addAll(groupPermissions.get(group.id(), keycloakAdminClient::groupPermissions));
                continue;
            }
            if (segments.size() < 2 || !segments.get(0).equals(properties.getOrganisationsGroup())) {
                continue;
            }
            String organisationId = segments.get(1);
            Set<String> roles = rolesByOrganisation.computeIfAbsent(organisationId, id -> new LinkedHashSet<>());
            Set<String> directPermissions = directPermissionsByOrganisation.computeIfAbsent(organisationId, id -> new LinkedHashSet<>());

            // the group itself and its parents down to the organisation group
            for (int depth = 2; depth <= segments.size(); depth++) {
                String path = "/" + String.join("/", segments.subList(0, depth));
                String groupId = depth == segments.size() ? group.id() : Optional.ofNullable(groupsByPath.get(path)).map(GroupNode::id).orElse(null);
                if (groupId == null) {
                    continue;
                }
                roles.addAll(grantingRoles(groupRoles.get(groupId, keycloakAdminClient::groupRealmRoles), permissionsOfRole));
                directPermissions.addAll(groupPermissions.get(groupId, keycloakAdminClient::groupPermissions));
            }
        }

        Map<String, OrganisationAccess> organisations = new LinkedHashMap<>();
        rolesByOrganisation.forEach((organisationId, roles) -> {
            Set<String> granted = new LinkedHashSet<>(directPermissionsByOrganisation.getOrDefault(organisationId, Set.of()));
            roles.forEach(role -> granted.addAll(permissionsOfRole.getOrDefault(role, Set.of())));
            Set<String> modules = enabledModules(groupsByPath.get("/" + properties.getOrganisationsGroup() + "/" + organisationId));
            organisations.put(organisationId, new OrganisationAccess(organisationId, roles, modules, permissionCatalogue.filterByModules(granted, modules)));
        });
        return new UserAccess(username, organisations, platformPermissions);
    }

    private Map<String, GroupNode> loadOrganisationTree() {
        Map<String, GroupNode> groupsByPath = new LinkedHashMap<>();
        keycloakAdminClient.organisationTree().forEach(node -> groupsByPath.put(node.path(), node));
        return groupsByPath;
    }

    private Set<String> enabledModules(GroupNode organisationGroup) {
        List<String> values = organisationGroup == null ? null : organisationGroup.attributes().get(MODULES_ATTRIBUTE);
        if (values == null) {
            return permissionCatalogue.getModules();
        }
        Set<String> enabled = new LinkedHashSet<>();
        values.forEach(value -> Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(module -> !module.isEmpty())
                .forEach(enabled::add));
        return enabled;
    }

    private Optional<String> userId(Jwt jwt, String username) {
        // Realms imported from older exports have no "basic" client scope, so the token may not carry "sub"
        if (jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
            return Optional.of(jwt.getSubject());
        }
        return userIds.get(username, keycloakAdminClient::findUserId);
    }

    /**
     * Keeps only the roles that grant permissions, so Keycloak defaults like offline_access are not reported.
     */
    private static Set<String> grantingRoles(Set<String> roles, Map<String, Set<String>> permissionsOfRole) {
        Set<String> result = new LinkedHashSet<>();
        roles.stream().filter(role -> !permissionsOfRole.getOrDefault(role, Set.of()).isEmpty()).forEach(result::add);
        return result;
    }

    private static List<String> pathSegments(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        return segments;
    }

}
