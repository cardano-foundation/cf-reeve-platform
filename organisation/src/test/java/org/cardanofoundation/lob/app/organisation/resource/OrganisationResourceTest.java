package org.cardanofoundation.lob.app.organisation.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.ResponseEntity;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.AccountEvent;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.request.OrganisationCreate;
import org.cardanofoundation.lob.app.organisation.domain.request.OrganisationUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationEventView;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationValidationView;
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
        assertEquals(200, listResponseEntity.getStatusCode().value());
        assertEquals(1, Objects.requireNonNull(listResponseEntity.getBody()).size());
        assertEquals(view, listResponseEntity.getBody().getFirst());

    }

    @Test
    void organsationDetailSpecificTest_error() {
        when(organisationService.findById("123")).thenReturn(Optional.empty());

        ResponseEntity<?> responseEntity = organisationResource.organisationDetailSpecific("123");

        assertEquals(404, responseEntity.getStatusCode().value());
    }

    @Test
    void organisationDetailSpecific_success() {
        Organisation org = mock(Organisation.class);
        OrganisationView view = mock(OrganisationView.class);
        when(organisationService.findById("123")).thenReturn(Optional.of(org));
        when(organisationService.getOrganisationView(org)).thenReturn(view);

        ResponseEntity<?> responseEntity = organisationResource.organisationDetailSpecific("123");

        assertEquals(200, responseEntity.getStatusCode().value());
        assertEquals(Optional.of(view), responseEntity.getBody());
    }

    @Test
    void organisationEvent() {
        when(organisationService.getOrganisationEventCode("123")).thenReturn(Set.of(AccountEvent.builder()
                        .id(new AccountEvent.Id("123", "456", "789"))
                        .name("Test Event")
                        .customerCode("Test Code")
                .build()));
        ResponseEntity<List<OrganisationEventView>> responseEntity = organisationResource.organisationEvent("123");
        assertEquals(200, responseEntity.getStatusCode().value());
        assertEquals(1, Objects.requireNonNull(responseEntity.getBody()).size());
        assertEquals("Test Event", responseEntity.getBody().getFirst().getName());

    }

    @Test
    void organisationCreate_error() {
        Organisation org = mock(Organisation.class);
        OrganisationCreate request = mock(OrganisationCreate.class);
        when(request.getCountryCode()).thenReturn("countryCode");
        when(request.getTaxIdNumber()).thenReturn("TaxId");
        when(organisationService.findById("ce65239c88cb26981fd2c911f35766788d33ca8383649e76105943592916d0a9")).thenReturn(Optional.of(org));

        ResponseEntity<?> responseEntity = organisationResource.organisationCreate(request);
        assertEquals(404, responseEntity.getStatusCode().value());
    }

    @Test
    void organisationCreate_errorOnCreate() {
        OrganisationCreate request = mock(OrganisationCreate.class);
        when(request.getCountryCode()).thenReturn("countryCode");
        when(request.getTaxIdNumber()).thenReturn("TaxId");
        when(organisationService.createOrganisation(request)).thenReturn(Optional.empty());
        when(organisationService.findById("ce65239c88cb26981fd2c911f35766788d33ca8383649e76105943592916d0a9")).thenReturn(Optional.empty());
        ResponseEntity<?> responseEntity = organisationResource.organisationCreate(request);
        assertEquals(404, responseEntity.getStatusCode().value());
    }

    @Test
    void organisationCreate_success() {
        OrganisationCreate request = mock(OrganisationCreate.class);
        Organisation organisation = mock(Organisation.class);
        OrganisationView view = mock(OrganisationView.class);
        when(request.getCountryCode()).thenReturn("countryCode");
        when(request.getTaxIdNumber()).thenReturn("TaxId");
        when(organisationService.createOrganisation(request)).thenReturn(Optional.of(organisation));
        when(organisationService.findById("ce65239c88cb26981fd2c911f35766788d33ca8383649e76105943592916d0a9")).thenReturn(Optional.empty());
        when(organisationService.getOrganisationView(organisation)).thenReturn(view);

        ResponseEntity<?> responseEntity = organisationResource.organisationCreate(request);
        assertEquals(200, responseEntity.getStatusCode().value());
        assertEquals(view, responseEntity.getBody());
    }

    @Test
    void organisationUpdate_organisationNotFound() {
        OrganisationUpdate request = mock(OrganisationUpdate.class);
        Organisation org = mock(Organisation.class);
        when(organisationService.findById("123")).thenReturn(Optional.of(org));

        ResponseEntity<?> responseEntity = organisationResource.organisationUpdate("123", request);
        assertEquals(404, responseEntity.getStatusCode().value());

    }

    @Test
    void organisationUpdate_errorOnUpdate() {
        OrganisationUpdate request = mock(OrganisationUpdate.class);
        Organisation org = mock(Organisation.class);
        when(organisationService.findById("123")).thenReturn(Optional.of(org));
        when(organisationService.upsertOrganisation(org, request)).thenReturn(Optional.empty());

        ResponseEntity<?> responseEntity = organisationResource.organisationUpdate("123", request);
        assertEquals(404, responseEntity.getStatusCode().value());
    }

    @Test
    void organisationUpdate_success() {
        OrganisationUpdate request = mock(OrganisationUpdate.class);
        Organisation org = mock(Organisation.class);
        OrganisationView view = mock(OrganisationView.class);
        when(organisationService.findById("123")).thenReturn(Optional.of(org));
        when(organisationService.upsertOrganisation(org, request)).thenReturn(Optional.of(org));
        when(organisationService.getOrganisationView(org)).thenReturn(view);

        ResponseEntity<?> responseEntity = organisationResource.organisationUpdate("123", request);
        assertEquals(200, responseEntity.getStatusCode().value());
        assertEquals(view, responseEntity.getBody());
    }

    @Test
    void validateOrganisation_noAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg("123")).thenReturn(false);

        ResponseEntity<?> responseEntity = organisationResource.validateOrganisation("123");
        assertEquals(403, responseEntity.getStatusCode().value());
    }

    @Test
    void validateOrganisation_notFound() {
        when(keycloakSecurityHelper.canUserAccessOrg("123")).thenReturn(true);
        when(organisationService.findById("123")).thenReturn(Optional.empty());

        ResponseEntity<?> responseEntity = organisationResource.validateOrganisation("123");
        assertEquals(404, responseEntity.getStatusCode().value());
    }

    @Test
    void validateOrganisation_success() {
        Organisation org = mock(Organisation.class);
        OrganisationValidationView validationView = mock(OrganisationValidationView.class);
        when(keycloakSecurityHelper.canUserAccessOrg("123")).thenReturn(true);
        when(organisationService.findById("123")).thenReturn(Optional.of(org));
        when(organisationService.validateOrganisation(org)).thenReturn(validationView);
        ResponseEntity<?> responseEntity = organisationResource.validateOrganisation("123");
        assertEquals(200, responseEntity.getStatusCode().value());
        assertEquals(validationView, responseEntity.getBody());
    }


}
