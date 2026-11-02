package org.cardanofoundation.lob.app.support.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private final PermissionCatalogue catalogue = new PermissionCatalogue();
    private KeycloakOrganisationProvisioner provisioner;

    // relative group path -> id handed out by the mocked Keycloak
    private final Map<String, String> created = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        KeycloakAdminProperties properties = new KeycloakAdminProperties("http://kc", "reeve-master", "reeve-backend", "secret",
                "reeve-api", "organisations", Duration.ofSeconds(30));
        provisioner = new KeycloakOrganisationProvisioner(client, properties, catalogue);

        when(client.findGroupIdByPath("organisations")).thenReturn(Optional.of("root"));
        when(client.childGroups("root")).thenReturn(List.of());
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
    void createsTheDefaultTreeAndAddsNobody() {
        provisioner.organisationCreated("org-new", "New Org");

        // every subgroup of the default layout exists, with its role
        assertThat(created.keySet()).containsAll(catalogue.getGroupLayout().keySet());
        verify(client).assignRealmRoleToGroup(created.get(""), "org_member");
        verify(client).assignRealmRoleToGroup(created.get("transactions"), "transactions_viewer");
        verify(client).assignRealmRoleToGroup(created.get("transactions/setup/erp"), "transactions_erp");
        verify(client).assignRealmRoleToGroup(created.get("presets/account_manager"), "preset_account_manager");

        // users are assigned by an administrator in Keycloak, not automatically
        verify(client, never()).addUserToGroup(anyString(), anyString());
    }

    @Test
    void existingOrganisationGroupIsLeftAlone() {
        when(client.childGroups("root")).thenReturn(List.of(new KeycloakAdminClient.GroupRef("g", "org-new", "/organisations/org-new")));

        provisioner.organisationCreated("org-new", "New Org");

        verify(client, never()).createChildGroup(anyString(), anyString(), any());
        verify(client, never()).addUserToGroup(anyString(), anyString());
    }

}
