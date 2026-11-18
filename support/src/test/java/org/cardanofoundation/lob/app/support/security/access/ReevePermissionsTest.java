package org.cardanofoundation.lob.app.support.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import org.junit.jupiter.api.Test;

/**
 * The catalogue the platform ships (reeve-permissions.yml) and how it reaches an application. This also guards the
 * names Spring would mangle if they were map keys ("/", "_").
 */
class ReevePermissionsTest {

    private final PermissionCatalogue catalogue = new PermissionCatalogue(PermissionCatalogueEnvironmentPostProcessor.shippedCatalogue());

    @Test
    void bindsTheModulesAndPermissions() {
        assertThat(catalogue.isConfigured()).isTrue();
        assertThat(catalogue.getModules()).containsExactly("transactions", "reporting", "funding", "document_vault");
        assertThat(catalogue.getPermissions()).hasSize(78).contains("document_vault:publish", "document_vault:delete_any", "transactions:view", "reports:publish", "organisations:create");
        assertThat(catalogue.getPlatformGroup()).isEqualTo("platform");
    }

    @Test
    void settingsBelongToTheModuleThatUsesThem() {
        assertThat(catalogue.modulesOf("cost_centers")).containsExactly("transactions");
        assertThat(catalogue.modulesOf("chart_of_accounts")).containsExactlyInAnyOrder("transactions", "reporting");
        assertThat(catalogue.modulesOf("export_setup_data")).containsExactlyInAnyOrder("transactions", "funding");
        assertThat(catalogue.modulesOf("data_explorer")).containsExactly("transactions");
        assertThat(catalogue.modulesOf("organization_details")).isEmpty();
    }

    @Test
    void keepsTheGroupPathsAndRoleNamesIntact() {
        assertThat(catalogue.getOrganisationRole()).isEqualTo("org_member");
        assertThat(catalogue.getGroupLayout())
                .hasSize(24)
                .containsEntry("transactions", "transactions_viewer")
                .containsEntry("transactions/setup/erp", "transactions_erp")
                .containsEntry("funding/project_manager", "funding_project_manager")
                .containsEntry("transactions/dashboard_editor", "transactions_dashboard_editor")
                .containsEntry("transactions/publish_viewer", "transactions_publish_viewer")
                .containsEntry("reporting/publish_viewer", "reports_publish_viewer")
                .containsEntry("document_vault/publisher", "document_vault_publisher")
                .doesNotContainKey("presets/accountant");
    }

    @Test
    void anythingTheApplicationSetsWins() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application", Map.of("lob.permissions.organisation-role", "custom_member")));

        new PermissionCatalogueEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        new PermissionCatalogueEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("lob.permissions.organisation-role")).isEqualTo("custom_member");
        assertThat(environment.getProperty("lob.permissions.platform.group")).isEqualTo("platform");
        assertThat(environment.getPropertySources().stream().filter(source -> source.getName().equals(PermissionCatalogueEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)))
                .as("added once, as the last source").hasSize(1);
        assertThat(environment.getPropertySources().stream().reduce((first, second) -> second).orElseThrow().getName())
                .isEqualTo(PermissionCatalogueEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
    }

    @Test
    void anApplicationGetsTheCatalogueWithoutConfiguringIt() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PermissionCatalogue.class)
                .web(WebApplicationType.NONE)
                .run()) {
            assertThat(context.getBean(PermissionCatalogue.class).getPermissions()).hasSize(78);
        }
    }

}
