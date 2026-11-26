package org.cardanofoundation.lob.app.support.security.access;

import java.time.Duration;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Connection to the Keycloak Admin REST API, used to read organisation memberships, roles and modules.
 * The client must be a confidential client with a service account that has the realm-management roles
 * view-users, query-groups, view-clients and view-realm (plus manage-users to provision new organisations).
 */
@Component
@Getter
@Slf4j
public class KeycloakAdminProperties {

    private final String url;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final String permissionsClientId;
    private final String organisationsGroup;
    private final Duration cacheTtl;

    public KeycloakAdminProperties(
            @Value("${lob.keycloak.admin.url:http://localhost:8080}") String url,
            @Value("${lob.keycloak.admin.realm:reeve-master}") String realm,
            @Value("${lob.keycloak.admin.client-id:reeve-backend}") String clientId,
            @Value("${lob.keycloak.admin.client-secret:}") String clientSecret,
            @Value("${lob.keycloak.admin.permissions-client-id:reeve-api}") String permissionsClientId,
            @Value("${lob.keycloak.admin.organisations-group:organisations}") String organisationsGroup,
            @Value("${lob.keycloak.admin.cache-ttl:PT30S}") Duration cacheTtl) {
        this.url = url;
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.permissionsClientId = permissionsClientId;
        this.organisationsGroup = organisationsGroup;
        this.cacheTtl = cacheTtl;
        if (clientSecret.isBlank()) {
            log.warn("lob.keycloak.admin.client-secret (LOB_KEYCLOAK_ADMIN_CLIENT_SECRET) is not set: nobody will get organisation access");
        }
    }

}
