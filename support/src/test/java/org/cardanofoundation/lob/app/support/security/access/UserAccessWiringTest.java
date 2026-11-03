package org.cardanofoundation.lob.app.support.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import org.junit.jupiter.api.Test;

/**
 * Starts the access beans the way Spring does.
 */
class UserAccessWiringTest {

    @Configuration
    @ComponentScan(basePackageClasses = PermissionCatalogue.class)
    static class AccessBeans {
    }

    // Like a Spring Boot app: ${...} placeholders resolved and "PT30S" converted to a Duration
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(AccessBeans.class);

    @Test
    void wiresTheKeycloakBeans() {
        contextRunner
                .withPropertyValues("lob.keycloak.admin.client-secret=secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(UserAccessResolver.class).isInstanceOf(KeycloakUserAccessResolver.class);
                    assertThat(context).getBean(OrganisationAccessProvisioner.class).isInstanceOf(KeycloakOrganisationProvisioner.class);
                    assertThat(context).hasSingleBean(KeycloakAdminClient.class);
                    assertThat(context).hasBean("permissions");
                });
    }

    @Test
    void startsWithoutASecret_soOtherModulesAndTestsStillBoot() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }

}
