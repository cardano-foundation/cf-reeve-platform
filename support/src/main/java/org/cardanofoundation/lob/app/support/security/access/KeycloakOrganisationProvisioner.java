package org.cardanofoundation.lob.app.support.security.access;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import org.cardanofoundation.lob.app.support.security.access.KeycloakAdminClient.GroupNode;

/**
 * Creates {@code /organisations/<organisation name>} (organisation id in the {@code organisationId} attribute, every
 * module enabled, the organisation role mapped on it) with the default subgroup layout of the {@link PermissionCatalogue}.
 * Nobody is added to it: an administrator assigns users in the Keycloak admin console. The group name follows the
 * organisation name, so the admin console stays readable.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KeycloakOrganisationProvisioner implements OrganisationAccessProvisioner {

    private static final int SHORT_ID_LENGTH = 8;

    private final KeycloakAdminClient keycloakAdminClient;
    private final KeycloakAdminProperties properties;
    private final PermissionCatalogue permissionCatalogue;

    @Override
    public void organisationCreated(String organisationId, String organisationName) {
        try {
            String parentId = keycloakAdminClient.findGroupIdByPath(properties.getOrganisationsGroup())
                    .orElseGet(() -> keycloakAdminClient.createTopLevelGroup(properties.getOrganisationsGroup()));

            List<GroupNode> existing = keycloakAdminClient.organisationGroups();
            if (existing.stream().anyMatch(group -> group.organisationId().filter(organisationId::equals).isPresent())) {
                log.info("Keycloak group for organisation {} already exists, not provisioning it again", organisationId);
                return;
            }

            Map<String, List<String>> attributes = new LinkedHashMap<>();
            attributes.put(KeycloakAdminClient.ORGANISATION_ID_ATTRIBUTE, List.of(organisationId));
            attributes.put("modules", List.copyOf(permissionCatalogue.getModules()));
            String organisationGroupId = keycloakAdminClient.createChildGroup(parentId, availableName(organisationName, organisationId, existing), attributes);
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

    @Override
    public void organisationRenamed(String organisationId, String organisationName) {
        try {
            List<GroupNode> groups = keycloakAdminClient.organisationGroups();
            Optional<GroupNode> organisationGroup = groups.stream()
                    .filter(group -> group.organisationId().filter(organisationId::equals).isPresent())
                    .findFirst();
            if (organisationGroup.isEmpty()) {
                log.warn("No Keycloak group for organisation {}, not renaming it", organisationId);
                return;
            }
            List<GroupNode> others = groups.stream().filter(group -> group != organisationGroup.get()).toList();
            String name = availableName(organisationName, organisationId, others);
            if (!name.equals(organisationGroup.get().name())) {
                keycloakAdminClient.renameGroup(organisationGroup.get(), name);
            }
        } catch (RuntimeException e) {
            // Only the label in the admin console is out of date; access works through the organisationId attribute
            log.error("Unable to rename the Keycloak group of organisation {}", organisationId, e);
        }
    }

    /**
     * The organisation name as a group name: "/" separates groups in a path, and sibling groups need different names
     * (organisation names aren't unique), so a taken name gets the start of the id.
     */
    private static String availableName(String organisationName, String organisationId, List<GroupNode> otherGroups) {
        String name = organisationName == null || organisationName.isBlank() ? organisationId : organisationName.trim().replace('/', '-');
        boolean taken = otherGroups.stream().anyMatch(group -> group.name().equals(name));
        return taken ? "%s (%s)".formatted(name, organisationId.substring(0, Math.min(SHORT_ID_LENGTH, organisationId.length()))) : name;
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
