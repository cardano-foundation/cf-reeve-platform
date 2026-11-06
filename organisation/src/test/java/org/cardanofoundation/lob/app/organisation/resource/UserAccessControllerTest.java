package org.cardanofoundation.lob.app.organisation.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.ResponseEntity;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.view.UserAccessView;
import org.cardanofoundation.lob.app.organisation.service.OrganisationService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;
import org.cardanofoundation.lob.app.support.security.access.CurrentUserAccess;
import org.cardanofoundation.lob.app.support.security.access.OrganisationAccess;
import org.cardanofoundation.lob.app.support.security.access.UserAccess;

@ExtendWith(MockitoExtension.class)
class UserAccessControllerTest {

    @Mock
    private CurrentUserAccess currentUserAccess;
    @Mock
    private OrganisationService organisationService;
    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @InjectMocks
    private UserAccessController userAccessController;

    @Test
    void access_listsEveryOrganisationWithSortedRolesModulesAndPermissions() {
        Map<String, OrganisationAccess> organisations = new LinkedHashMap<>();
        organisations.put("org-a", new OrganisationAccess("org-a", Set.of("transactions_reviewer"), Set.of("transactions", "funding"),
                Set.of("transactions:view", "project_management:view")));
        organisations.put("org-b", new OrganisationAccess("org-b", Set.of("reports_viewer"), Set.of("reporting"), Set.of("reports:view")));
        when(currentUserAccess.get()).thenReturn(new UserAccess("multi", organisations));
        when(keycloakSecurityHelper.getCurrentUser()).thenReturn("Multi Org");
        Organisation orgA = mock(Organisation.class);
        when(orgA.getName()).thenReturn("Organisation A");
        when(organisationService.findById("org-a")).thenReturn(Optional.of(orgA));
        when(organisationService.findById("org-b")).thenReturn(Optional.empty());

        ResponseEntity<UserAccessView> response = userAccessController.access();

        assertEquals(200, response.getStatusCode().value());
        UserAccessView body = response.getBody();
        assertEquals("multi", body.username());
        assertEquals("Multi Org", body.name());
        assertEquals(2, body.organisations().size());
        UserAccessView.OrganisationAccessView a = body.organisations().get(0);
        assertEquals("Organisation A", a.name());
        assertEquals(List.of("funding", "transactions"), a.modules());
        assertEquals(List.of("project_management:view", "transactions:view"), a.permissions());
        // a Keycloak organisation that doesn't exist in the platform yet has no name
        assertNull(body.organisations().get(1).name());
    }

}
