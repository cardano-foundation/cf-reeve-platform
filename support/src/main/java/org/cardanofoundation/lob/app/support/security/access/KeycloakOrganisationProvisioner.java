package org.cardanofoundation.lob.app.support.security.access;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;


/**
 * Creates {@code /organisations/<organisationId>} (every module enabled, the organisation role mapped on it) with the
 * default subgroup layout of the {@link PermissionCatalogue}. Nobody is added to it: an administrator assigns users
 * in the Keycloak admin console.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KeycloakOrganisationProvisioner implements OrganisationAccessProvisioner {

    private final KeycloakAdminClient keycloakAdminClient;
    private final KeycloakAdminProperties properties;
    private final PermissionCatalogue permissionCatalogue;

    @Override
    public void organisationCreated(String organisationId, String organisationName) {
        try {
            String parentId = keycloakAdminClient.findGroupIdByPath(properties.getOrganisationsGroup())
                    .orElseGet(() -> keycloakAdminClient.createTopLevelGroup(properties.getOrganisationsGroup()));

            if (keycloakAdminClient.childGroups(parentId).stream().anyMatch(group -> group.name().equals(organisationId))) {
                log.info("Keycloak group for organisation {} already exists, not provisioning it again", organisationId);
                return;
            }

            Map<String, List<String>> attributes = new LinkedHashMap<>();
            attributes.put("modules", List.copyOf(permissionCatalogue.getModules()));
            attributes.put("displayName", List.of(organisationName));
            String organisationGroupId = keycloakAdminClient.createChildGroup(parentId, organisationId, attributes);
            keycloakAdminClient.assignRealmRoleToGroup(organisationGroupId, permissionCatalogue.getOrganisationRole());

            Map<String, String> groupIdsByRelativePath = new LinkedHashMap<>();
            permissionCatalogue.getGroupLayout().forEach((relativePath, role) -> {
                String groupId = ensureGroup(organisationGroupId, relativePath, groupIdsByRelativePath);
                keycloakAdminClient.assignRealmRoleToGroup(groupId, role);
            });

            log.info("Provisioned Keycloak groups for organisation {} ({})", organisationId, organisationName);
        } catch (RuntimeException e) {
            // The organisation itself is created; access can still be granted manually in Keycloak
            log.error("Unable to provision the Keycloak groups of organisation {}", organisationId, e);
        }
    }

    /**
     * Creates "transactions/reviewer" as a folder group "transactions" with a child "reviewer".
     */
    private String ensureGroup(String organisationGroupId, String relativePath, Map<String, String> groupIdsByRelativePath) {
        String parentId = organisationGroupId;
        StringBuilder path = new StringBuilder();
        for (String segment : relativePath.split("/")) {
            path.append(path.isEmpty() ? "" : "/").append(segment);
            String existing = groupIdsByRelativePath.get(path.toString());
            if (existing == null) {
                existing = keycloakAdminClient.createChildGroup(parentId, segment, Map.of());
                groupIdsByRelativePath.put(path.toString(), existing);
            }
            parentId = existing;
        }
        return parentId;
    }

}
