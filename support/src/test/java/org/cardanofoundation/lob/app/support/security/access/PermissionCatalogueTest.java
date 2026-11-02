package org.cardanofoundation.lob.app.support.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class PermissionCatalogueTest {

    private final PermissionCatalogue catalogue = new PermissionCatalogue();

    @Test
    void loadsModulesAndPermissions() {
        assertThat(catalogue.getModules()).containsExactly("transactions", "reporting", "funding");
        assertThat(catalogue.getPermissions()).contains("transactions:view", "reports:publish", "project_management:create_project");
    }

    @Test
    void settingsBelongToTheModuleThatUsesThem() {
        assertThat(catalogue.modulesOf("cost_centers")).containsExactly("transactions");
        assertThat(catalogue.modulesOf("accounting_regimes")).containsExactly("reporting");
        assertThat(catalogue.modulesOf("chart_of_accounts")).containsExactlyInAnyOrder("transactions", "reporting");
        assertThat(catalogue.modulesOf("project_management")).containsExactly("funding");
        assertThat(catalogue.modulesOf("currencies")).isEmpty();
        assertThat(catalogue.modulesOf("organization_details")).isEmpty();
    }

    @Test
    void filterByModules_keepsCoreAndResourcesOfAnyEnabledModule() {
        Set<String> granted = Set.of("transactions:view", "reports:view", "project_management:view", "chart_of_accounts:view", "currencies:view");

        assertThat(catalogue.filterByModules(granted, Set.of("reporting")))
                .containsExactlyInAnyOrder("reports:view", "chart_of_accounts:view", "currencies:view");
        assertThat(catalogue.filterByModules(granted, Set.of("funding")))
                .containsExactlyInAnyOrder("project_management:view", "currencies:view");
    }

    @Test
    void organisationRoleAndGroupLayout() {
        assertThat(catalogue.getOrganisationRole()).isEqualTo("org_member");
        assertThat(catalogue.getGroupLayout())
                // module folders carry the viewer role, so every stage below inherits read access
                .containsEntry("transactions", "transactions_viewer")
                .containsEntry("transactions/reviewer", "transactions_reviewer")
                // four-eyes: publisher next to reviewer, not under it
                .containsEntry("transactions/publisher", "transactions_publisher")
                .containsEntry("transactions/setup/erp", "transactions_erp")
                .containsEntry("funding", "funding_viewer")
                .containsEntry("presets/accountant", "preset_accountant")
                .doesNotContainKey("transactions/viewer")
                .doesNotContainValue("org_member");
    }


    @Test
    void platform() {
        assertThat(catalogue.getPermissions()).contains("organisations:create").doesNotContain("organization_details:create");
        assertThat(catalogue.modulesOf("organisations")).isEmpty();
        assertThat(catalogue.getPlatformGroup()).isEqualTo("platform");
    }

}
