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
 *     <li>an organisation is a group under {@code /organisations}, named after the organisation; its {@code organisationId}
 *     attribute holds the organisation id (a group without it gives no access), so renaming the group changes nothing else,
 *     and its {@code modules} attribute lists the enabled modules (missing attribute = every module enabled)</li>
 *     <li>being in any group under it makes the user a member of that organisation</li>
 *     <li>the user gets the realm roles (and permissions) mapped to the groups they are in <b>and to their parent groups
 *     up to the organisation group</b>, so a role mapped on {@code /organisations/<name>} (org_member) applies to
 *     every member, and roles of e.g. {@code /organisations/<name>/transactions/reviewer} only in that organisation</li>
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
    private static final String PATH_SEPARATOR = "/";

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
            return resolveWithOneRetry(userId.get(), username);
        } catch (RuntimeException e) {
            log.error("Unable to resolve the organisation access of user {} from Keycloak, no access granted", username, e);
            return UserAccess.none(username);
        }
    }

    /**
     * Keycloak may have been reset or reconfigured (new realm keys, recreated client, new group ids): on a failure,
     * drop everything remembered about it and try once more before refusing access.
     */
    private UserAccess resolveWithOneRetry(String userId, String username) {
        try {
            return userAccess.get(userId, id -> load(id, username));
        } catch (RuntimeException firstFailure) {
            log.warn("Organisation access lookup for user {} failed, retrying with fresh Keycloak state: {}", username, firstFailure.getMessage());
            forgetKeycloakState();
            return userAccess.get(userId, id -> load(id, username));
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
        Map<String, GroupNode> organisationGroups = new LinkedHashMap<>();
        Set<String> platformPermissions = new LinkedHashSet<>();

        for (GroupRef group : keycloakAdminClient.userGroups(userId)) {
            List<String> segments = pathSegments(group.path());
            if (!segments.isEmpty() && segments.get(0).equals(permissionCatalogue.getPlatformGroup())) {
                addPlatformGroup(group, permissionsOfRole, platformPermissions);
            } else if (segments.size() >= 2 && segments.get(0).equals(properties.getOrganisationsGroup())) {
                String organisationPath = groupPath(segments.subList(0, 2));
                groupsByPath = treeContaining(organisationPath, groupsByPath);
                GroupNode organisationGroup = groupsByPath.get(organisationPath);
                Optional<String> organisationIdAttribute = Optional.ofNullable(organisationGroup).flatMap(GroupNode::organisationId);
                if (organisationIdAttribute.isEmpty()) {
                    log.warn("Group {} has no {} attribute, it gives user {} no access", organisationPath, KeycloakAdminClient.ORGANISATION_ID_ATTRIBUTE, username);
                    continue;
                }
                String organisationId = organisationIdAttribute.get();
                organisationGroups.put(organisationId, organisationGroup);
                addOrganisationGroup(group, segments, groupsByPath, permissionsOfRole,
                        rolesByOrganisation.computeIfAbsent(organisationId, id -> new LinkedHashSet<>()),
                        directPermissionsByOrganisation.computeIfAbsent(organisationId, id -> new LinkedHashSet<>()));
            }
        }

        Map<String, OrganisationAccess> organisations = new LinkedHashMap<>();
        rolesByOrganisation.forEach((organisationId, roles) -> {
            Set<String> granted = new LinkedHashSet<>(directPermissionsByOrganisation.getOrDefault(organisationId, Set.of()));
            roles.forEach(role -> granted.addAll(permissionsOfRole.getOrDefault(role, Set.of())));
            Set<String> modules = enabledModules(organisationGroups.get(organisationId));
            organisations.put(organisationId, new OrganisationAccess(organisationId, roles, modules, permissionCatalogue.filterByModules(granted, modules)));
        });
        return new UserAccess(username, organisations, platformPermissions);
    }

    /**
     * Platform-wide access, e.g. /platform/administrators: the roles of the group (no organisation, no modules).
     */
    private void addPlatformGroup(GroupRef group, Map<String, Set<String>> permissionsOfRole, Set<String> platformPermissions) {
        grantingRoles(groupRoles.get(group.id(), keycloakAdminClient::groupRealmRoles), permissionsOfRole)
                .forEach(role -> platformPermissions.addAll(permissionsOfRole.getOrDefault(role, Set.of())));
        platformPermissions.addAll(groupPermissions.get(group.id(), keycloakAdminClient::groupPermissions));
    }

    /**
     * The roles and permissions of the group itself and of its parents down to the organisation group.
     */
    private void addOrganisationGroup(GroupRef group, List<String> segments, Map<String, GroupNode> groupsByPath,
                                      Map<String, Set<String>> permissionsOfRole, Set<String> roles, Set<String> directPermissions) {
        for (int depth = 2; depth <= segments.size(); depth++) {
            String groupId = depth == segments.size()
                    ? group.id()
                    : Optional.ofNullable(groupsByPath.get(groupPath(segments.subList(0, depth)))).map(GroupNode::id).orElse(null);
            if (groupId != null) {
                roles.addAll(grantingRoles(groupRoles.get(groupId, keycloakAdminClient::groupRealmRoles), permissionsOfRole));
                directPermissions.addAll(groupPermissions.get(groupId, keycloakAdminClient::groupPermissions));
            }
        }
    }

    private static String groupPath(List<String> segments) {
        return PATH_SEPARATOR + String.join(PATH_SEPARATOR, segments);
    }

    /**
     * A user's group that isn't in the cached tree was just created or renamed: load the tree again.
     */
    private Map<String, GroupNode> treeContaining(String path, Map<String, GroupNode> groupsByPath) {
        if (groupsByPath.containsKey(path)) {
            return groupsByPath;
        }
        organisationTree.evict(ALL);
        return organisationTree.get(ALL, key -> loadOrganisationTree());
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
        for (String segment : path.split(PATH_SEPARATOR)) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        return segments;
    }

}
