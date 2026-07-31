package org.cardanofoundation.lob.app.organisation.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.request.ReferenceCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.ReferenceCodeView;
import org.cardanofoundation.lob.app.organisation.service.OrganisationService;
import org.cardanofoundation.lob.app.organisation.service.ReferenceCodeService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class ReferenceCodeResourceTest {

    @Mock
    private ReferenceCodeService referenceCodeService;
    @Mock
    private OrganisationService organisationService;
    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @InjectMocks
    private ReferenceCodeResource controller;

    @BeforeEach
    void setUp() {
        lenient().when(keycloakSecurityHelper.canUserAccessOrg(any())).thenReturn(true);
    }

    @Test
    void insertReferenceCodeByCsv_error() {
        when(referenceCodeService.insertReferenceCodeByCsv("orgId", null)).thenReturn(Either.left(
                List.of(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Detail"))));

        ResponseEntity<?> response = controller.insertRefCodeByCsv("orgId", null);

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody()).isInstanceOf(List.class);
        assertThat(((List<?>) response.getBody())).hasSize(1);
    }

    @Test
    void insertReferenceCodeByCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        ReferenceCodeView view = mock(ReferenceCodeView.class);
        when(referenceCodeService.insertReferenceCodeByCsv("orgId", file)).thenReturn(Either.right(List.of(view)));

        ResponseEntity<?> response = controller.insertRefCodeByCsv("orgId", file);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(List.class);
        assertThat(((List<?>) response.getBody())).hasSize(1);
        assertThat(((List<?>) response.getBody()).iterator().next()).isEqualTo(view);
    }

    @Test
    void getReferenceCodes_noOrgAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg("orgId")).thenReturn(false);

        ResponseEntity<?> response = controller.getReferenceCodes("orgId", null, null, null, null, Pageable.unpaged());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(referenceCodeService);
    }

    @Test
    void downloadRefCodesCsv_noOrgAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg("orgId")).thenReturn(false);

        ResponseEntity<?> response = controller.downloadRefCodesCsv("orgId", null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(referenceCodeService);
    }

    @Test
    void insertReferenceCode_noOrgAccess() {
        ReferenceCodeUpdate update = mock(ReferenceCodeUpdate.class);
        when(keycloakSecurityHelper.canUserAccessOrg("orgId")).thenReturn(false);

        ResponseEntity<?> response = controller.insertReferenceCode("orgId", update);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(referenceCodeService);
    }

    @Test
    void insertRefCodeByCsv_noOrgAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg("orgId")).thenReturn(false);

        ResponseEntity<?> response = controller.insertRefCodeByCsv("orgId", null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(referenceCodeService);
    }

    @Test
    void updateReferenceCode_noOrgAccess() {
        ReferenceCodeUpdate update = mock(ReferenceCodeUpdate.class);
        when(keycloakSecurityHelper.canUserAccessOrg("orgId")).thenReturn(false);

        ResponseEntity<?> response = controller.updateReferenceCode("orgId", update);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(referenceCodeService);
    }

}
