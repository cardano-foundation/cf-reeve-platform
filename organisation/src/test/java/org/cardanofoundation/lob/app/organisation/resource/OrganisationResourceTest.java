package org.cardanofoundation.lob.app.organisation.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.ResponseEntity;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationCostCenter;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationProject;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationCostCenterView;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationProjectView;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationView;
import org.cardanofoundation.lob.app.organisation.service.OrganisationService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class OrganisationResourceTest {

    @Mock
    private OrganisationService organisationService;
    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @InjectMocks
    private OrganisationResource organisationResource;

    @Test
    void organisationList_success() {
        Organisation org = mock(Organisation.class);
        OrganisationView view = mock(OrganisationView.class);
        when(organisationService.findById("123")).thenReturn(Optional.of(org));
        when(organisationService.getOrganisationView(org)).thenReturn(view);

        ResponseEntity<List<OrganisationView>> listResponseEntity =
                organisationResource.organisationList(Optional.of(new String[]{"123"}));
        assertEquals(200, listResponseEntity.getStatusCodeValue());
        assertEquals(1, listResponseEntity.getBody().size());
        assertEquals(view, listResponseEntity.getBody().get(0));

    }

    @Test
    void organsationDetailSpecificTest_error() {
        when(organisationService.findById("123")).thenReturn(Optional.empty());

        ResponseEntity<?> responseEntity = organisationResource.organisationDetailSpecific("123");

        assertEquals(404, responseEntity.getStatusCodeValue());
    }

    @Test
    void organisationDetailSpecific_success() {
        Organisation org = mock(Organisation.class);
        OrganisationView view = mock(OrganisationView.class);
        when(organisationService.findById("123")).thenReturn(Optional.of(org));
        when(organisationService.getOrganisationView(org)).thenReturn(view);

        ResponseEntity<?> responseEntity = organisationResource.organisationDetailSpecific("123");

        assertEquals(200, responseEntity.getStatusCodeValue());
        assertEquals(Optional.of(view), responseEntity.getBody());
    }

    @Test
    void organisationCostCenter() {
        OrganisationCostCenter costCenter = mock(OrganisationCostCenter.class);
        OrganisationCostCenterView view = mock(OrganisationCostCenterView.class);
        when(organisationService.getAllCostCenter("123")).thenReturn(Set.of(costCenter));

        try(MockedStatic<OrganisationCostCenterView> mock = mockStatic(OrganisationCostCenterView.class)) {
            mock.when(() -> OrganisationCostCenterView.fromEntity(costCenter)).thenReturn(view);
            ResponseEntity<Set<OrganisationCostCenterView>> responseEntity = organisationResource.organisationCostCenter("123");

            assertEquals(200, responseEntity.getStatusCodeValue());
            assertEquals(1, responseEntity.getBody().size());
            assertEquals(view, responseEntity.getBody().iterator().next());
        }
    }

    @Test
    void organisationProject() {
        OrganisationProject project = mock(OrganisationProject.class);
        OrganisationProjectView view = mock(OrganisationProjectView.class);
        when(organisationService.getAllProjects("123")).thenReturn(Set.of(project));

        try(MockedStatic<OrganisationProjectView> mock = mockStatic(OrganisationProjectView.class)) {
            mock.when(() -> OrganisationProjectView.fromEntity(project)).thenReturn(view);
            ResponseEntity<Set<OrganisationProjectView>> responseEntity = organisationResource.organisationProject("123");

            assertEquals(200, responseEntity.getStatusCodeValue());
            assertEquals(1, responseEntity.getBody().size());
            assertEquals(view, responseEntity.getBody().iterator().next());
        }
    }
}
