package org.cardanofoundation.lob.app.support.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeycloakOrganisationProvisionerTest {

    private final KeycloakAdminClient client = mock(KeycloakAdminClient.class);
    private final PermissionCatalogue catalogue = new PermissionCatalogue(new PermissionProperties(
            List.of("transactions", "reporting", "funding"), "org_member",
            List.of(new PermissionProperties.Resource("transactions", List.of("transactions"), List.of("view"))),
            List.of(
                    new PermissionProperties.Group("transactions", "transactions_viewer"),
                    new PermissionProperties.Group("transactions/setup", "transactions_setup"),
                    new PermissionProperties.Group("transactions/setup/erp", "transactions_erp"),
                    new PermissionProperties.Group("funding", "funding_viewer"),
                    new PermissionProperties.Group("funding/setup", "funding_setup")),
            null));
    private KeycloakOrganisationProvisioner provisioner;

    // relative group path -> id handed out by the mocked Keycloak
    private final Map<String, String> created = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        KeycloakAdminProperties properties = new KeycloakAdminProperties("http://kc", "reeve-master", "reeve-backend", "secret",
                "reeve-api", "organisations", Duration.ofSeconds(30));
        provisioner = new KeycloakOrganisationProvisioner(client, properties, catalogue);

        when(client.findGroupIdByPath("organisations")).thenReturn(Optional.of("root"));
        when(client.organisationGroups()).thenReturn(List.of());
        when(client.createChildGroup(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String parentId = invocation.getArgument(0);
            String name = invocation.getArgument(1);
            String path;
            if (parentId.equals("root")) {
                path = ""; // the organisation group itself
            } else {
                String parentPath = created.entrySet().stream().filter(e -> e.getValue().equals(parentId)).map(Map.Entry::getKey).findFirst().orElseThrow();
                path = parentPath.isEmpty() ? name : parentPath + "/" + name;
            }
            String id = "id:" + path;
            created.put(path, id);
            return id;
        });
    }

    @Test
    void withoutACatalogue_createsNothing() {
        KeycloakOrganisationProvisioner unconfigured = new KeycloakOrganisationProvisioner(client, mock(KeycloakAdminProperties.class),
                new PermissionCatalogue(new PermissionProperties(null, null, null, null, null)));

        unconfigured.organisationCreated("org-new", "New Org");

        verifyNoInteractions(client);
    }

    @Test
    void createsTheDefaultTreeAndAddsNobody() {
        provisioner.organisationCreated("org-new", "New Org");

        // every subgroup of the default layout exists, with its role
        assertThat(created.keySet()).containsAll(catalogue.getGroupLayout().keySet());
        verify(client).assignRealmRoleToGroup(created.get(""), "org_member");
        verify(client).assignRealmRoleToGroup(created.get("transactions"), "transactions_viewer");
        verify(client).assignRealmRoleToGroup(created.get("transactions/setup/erp"), "transactions_erp");
        verify(client).assignRealmRoleToGroup(created.get("funding/setup"), "funding_setup");

        // the group is named after the organisation, the id is an attribute
        verify(client).createChildGroup("root", "New Org", Map.of(
                "organisationId", List.of("org-new"),
                "modules", List.copyOf(catalogue.getModules())));

        // users are assigned by an administrator in Keycloak, not automatically
        verify(client, never()).addUserToGroup(anyString(), anyString());
    }

    @Test
    void existingOrganisationGroupIsLeftAlone() {
        when(client.organisationGroups()).thenReturn(List.of(group("g", "Renamed in Keycloak", "org-new")));

        provisioner.organisationCreated("org-new", "New Org");

        verify(client, never()).createChildGroup(anyString(), anyString(), any());
        verify(client, never()).addUserToGroup(anyString(), anyString());
    }

    @Test
    void takenOrUnusableNamesAreMadeValid() {
        when(client.organisationGroups()).thenReturn(List.of(group("g", "New Org", "org-other")));

        provisioner.organisationCreated("org-new-with-long-id", "New Org");
        verify(client).createChildGroup(eq("root"), eq("New Org (org-new-)"), any());

        provisioner.organisationCreated("org-slash", "A/B Ltd");
        verify(client).createChildGroup(eq("root"), eq("A-B Ltd"), any());
    }

    @Test
    void createsTheOrganisationsGroupWhenItDoesNotExist() {
        when(client.findGroupIdByPath("organisations")).thenReturn(Optional.empty());
        when(client.createTopLevelGroup("organisations")).thenReturn("root");

        provisioner.organisationCreated("org-new", "New Org");

        verify(client).createTopLevelGroup("organisations");
        verify(client).createChildGroup(eq("root"), eq("New Org"), any());
    }

    @Test
    void blankNameFallsBackToTheOrganisationId() {
        provisioner.organisationCreated("org-new", " ");

        verify(client).createChildGroup(eq("root"), eq("org-new"), any());
    }

    @Test
    void failedProvisioningDoesNotFailTheOrganisationCreation() {
        when(client.findGroupIdByPath("organisations")).thenThrow(new IllegalStateException("Keycloak down"));

        provisioner.organisationCreated("org-new", "New Org");

        verify(client, never()).createChildGroup(anyString(), anyString(), any());
    }

    @Test
    void renamingAnOrganisationWithoutGroupChangesNothing() {
        when(client.organisationGroups()).thenReturn(List.of(group("g", "Other", "org-other")));

        provisioner.organisationRenamed("org-new", "New Name");

        verify(client, never()).renameGroup(any(), anyString());
    }

    @Test
    void renamingToANameTakenByAnotherOrganisationStaysUnique() {
        KeycloakAdminClient.GroupNode group = group("g", "Old Name", "org-new");
        when(client.organisationGroups()).thenReturn(List.of(group, group("g2", "New Name", "org-other")));

        provisioner.organisationRenamed("org-new", "New Name");

        verify(client).renameGroup(group, "New Name (org-new)");
    }

    @Test
    void renamingTheOrganisationRenamesItsGroup() {
        KeycloakAdminClient.GroupNode group = group("g", "Old Name", "org-new");
        when(client.organisationGroups()).thenReturn(List.of(group));

        provisioner.organisationRenamed("org-new", "New Name");

        verify(client).renameGroup(group, "New Name");
    }

    @Test
    void unchangedNameIsNotWrittenToKeycloak() {
        when(client.organisationGroups()).thenReturn(List.of(group("g", "Same Name", "org-new")));

        provisioner.organisationRenamed("org-new", "Same Name");

        verify(client, never()).renameGroup(any(), anyString());
    }

    @Test
    void failedRenameDoesNotFailTheUpdate() {
        when(client.organisationGroups()).thenThrow(new IllegalStateException("Keycloak down"));

        provisioner.organisationRenamed("org-new", "New Name");

        verify(client, never()).renameGroup(any(), anyString());
    }

    private static KeycloakAdminClient.GroupNode group(String id, String name, String organisationId) {
        return new KeycloakAdminClient.GroupNode(id, "/organisations/" + name, Map.of("organisationId", List.of(organisationId)));
    }

}
