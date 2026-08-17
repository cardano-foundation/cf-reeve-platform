package org.cardanofoundation.lob.app.support.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import org.junit.jupiter.api.Test;

class SecurityRolePropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(SecurityRoleProperties.class);

    @Test
    void usesDefaultRoles() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SecurityRoleProperties.class);
            assertThat(context).hasBean("securityConfig");

            SecurityRoleProperties properties = context.getBean(SecurityRoleProperties.class);
            assertThat(properties.getManagerRole()).isEqualTo("reeve_account_manager");
            assertThat(properties.getAuditorRole()).isEqualTo("reeve_auditor");
            assertThat(properties.getAccountantRole()).isEqualTo("reeve_accountant");
            assertThat(properties.getAdminRole()).isEqualTo("reeve_admin");
        });
    }

    @Test
    void usesConfiguredRoles() {
        contextRunner
                .withPropertyValues(
                        "lob.security.roles.manager=custom_manager",
                        "lob.security.roles.auditor=custom_auditor",
                        "lob.security.roles.accountant=custom_accountant",
                        "lob.security.roles.admin=custom_admin")
                .run(context -> {
                    SecurityRoleProperties properties = context.getBean(SecurityRoleProperties.class);
                    assertThat(properties.getManagerRole()).isEqualTo("custom_manager");
                    assertThat(properties.getAuditorRole()).isEqualTo("custom_auditor");
                    assertThat(properties.getAccountantRole()).isEqualTo("custom_accountant");
                    assertThat(properties.getAdminRole()).isEqualTo("custom_admin");
                });
    }
}
