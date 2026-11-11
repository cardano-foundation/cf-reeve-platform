package org.cardanofoundation.lob.app.support.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The logic only: the real catalogue (reeve-permissions.yml) is tested by ReevePermissionsTest.
 */
class PermissionCatalogueTest {

    private final PermissionCatalogue catalogue = new PermissionCatalogue(new PermissionProperties(
            List.of("transactions", "reporting", "funding"), "org_member",
            List.of(
                    new PermissionProperties.Resource("organization_details", List.of(), List.of("view")),
                    new PermissionProperties.Resource("transactions", List.of("transactions"), List.of("view", "export")),
                    new PermissionProperties.Resource("reports", List.of("reporting"), List.of("view")),
                    new PermissionProperties.Resource("project_management", List.of("funding"), List.of("view")),
                    new PermissionProperties.Resource("chart_of_accounts", List.of("transactions", "reporting"), List.of("view"))),
            List.of(new PermissionProperties.Group("transactions/setup/erp", "transactions_erp")),
            new PermissionProperties.Platform("platform")));

    @Test
    void buildsThePermissionsFromResourcesAndActions() {
        assertThat(catalogue.isConfigured()).isTrue();
        assertThat(catalogue.getModules()).containsExactly("transactions", "reporting", "funding");
        assertThat(catalogue.getPermissions()).containsExactly("organization_details:view", "transactions:view", "transactions:export",
                "reports:view", "project_management:view", "chart_of_accounts:view");
    }

    @Test
    void knowsTheModulesOfAResource() {
        assertThat(catalogue.modulesOf("transactions")).containsExactly("transactions");
        assertThat(catalogue.modulesOf("chart_of_accounts")).containsExactlyInAnyOrder("transactions", "reporting");
        assertThat(catalogue.modulesOf("organization_details")).isEmpty();
    }

    @Test
    void filterByModules_keepsCoreAndResourcesOfAnyEnabledModule() {
        Set<String> granted = Set.of("organization_details:view", "transactions:view", "reports:view", "project_management:view", "chart_of_accounts:view");

        assertThat(catalogue.filterByModules(granted, Set.of("reporting")))
                .containsExactlyInAnyOrder("organization_details:view", "reports:view", "chart_of_accounts:view");
        assertThat(catalogue.filterByModules(granted, Set.of("funding")))
                .containsExactlyInAnyOrder("organization_details:view", "project_management:view");
    }

    @Test
    void organisationRoleAndGroupLayout() {
        assertThat(catalogue.getOrganisationRole()).isEqualTo("org_member");
        assertThat(catalogue.getGroupLayout()).containsExactly(entry("transactions/setup/erp", "transactions_erp"));
        assertThat(catalogue.getPlatformGroup()).isEqualTo("platform");
    }

    @Test
    void withoutACatalogue_grantsNothing() {
        PermissionCatalogue unconfigured = new PermissionCatalogue(new PermissionProperties(null, null, null, null, null));

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThat(unconfigured.filterByModules(Set.of("transactions:view"), Set.of("transactions"))).isEmpty();
        assertThat(unconfigured.getPlatformGroup()).isEqualTo("platform");
    }

}
