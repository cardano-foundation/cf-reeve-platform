package org.cardanofoundation.lob.app.support.security;

import lombok.Getter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component("securityConfig")
@Getter
public class SecurityRoleProperties {

    private final String managerRole;
    private final String auditorRole;
    private final String accountantRole;
    private final String adminRole;

    public SecurityRoleProperties(
            @Value("${lob.security.roles.manager:reeve_account_manager}") String managerRole,
            @Value("${lob.security.roles.auditor:reeve_auditor}") String auditorRole,
            @Value("${lob.security.roles.accountant:reeve_accountant}") String accountantRole,
            @Value("${lob.security.roles.admin:reeve_admin}") String adminRole) {
        this.managerRole = managerRole;
        this.auditorRole = auditorRole;
        this.accountantRole = accountantRole;
        this.adminRole = adminRole;
    }
}
