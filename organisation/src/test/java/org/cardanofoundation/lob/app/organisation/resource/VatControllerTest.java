package org.cardanofoundation.lob.app.organisation.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import org.cardanofoundation.lob.app.organisation.domain.request.VatUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.VatView;
import org.cardanofoundation.lob.app.organisation.service.VatService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class VatControllerTest {

    @Mock
    private VatService vatService;
    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @InjectMocks
    private VatController vatController;

    private final String orgId = "org123";

    @BeforeEach
    void setUp() {
        lenient().when(keycloakSecurityHelper.canUserAccessOrg(any())).thenReturn(true);
    }

    @Test
    void getVatCodes_success() {
        VatView view = mock(VatView.class);
        when(vatService.findAllByOrganisationId(orgId, null, null, null, null, null, null, Pageable.unpaged()))
                .thenReturn(Either.right(List.of(view)));

        ResponseEntity<?> response = vatController.getVatCodes(orgId, null, null, null, null, null, null, Pageable.unpaged());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(view), response.getBody());
    }

    @Test
    void getVatCodes_noOrgAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg(orgId)).thenReturn(false);

        ResponseEntity<?> response = vatController.getVatCodes(orgId, null, null, null, null, null, null, Pageable.unpaged());

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(vatService);
    }

    @Test
    void downloadVatCodesCsv_noOrgAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg(orgId)).thenReturn(false);

        ResponseEntity<?> response = vatController.downloadVatCodesCsv(orgId, null, null, null, null, null, null);

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(vatService);
    }

    @Test
    void insertVatCode_success() {
        VatUpdate update = mock(VatUpdate.class);
        VatView view = mock(VatView.class);
        when(view.getError()).thenReturn(java.util.Optional.empty());
        when(vatService.insert(orgId, update, false)).thenReturn(view);

        ResponseEntity<?> response = vatController.insertVatCode(orgId, update);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(view, response.getBody());
    }

    @Test
    void insertVatCode_noOrgAccess() {
        VatUpdate update = mock(VatUpdate.class);
        when(keycloakSecurityHelper.canUserAccessOrg(orgId)).thenReturn(false);

        ResponseEntity<?> response = vatController.insertVatCode(orgId, update);

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(vatService);
    }

    @Test
    void updateReferenceCode_success() {
        VatUpdate update = mock(VatUpdate.class);
        VatView view = mock(VatView.class);
        when(view.getError()).thenReturn(java.util.Optional.empty());
        when(vatService.update(orgId, update)).thenReturn(view);

        ResponseEntity<?> response = vatController.updateReferenceCode(orgId, update);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(view, response.getBody());
    }

    @Test
    void updateReferenceCode_noOrgAccess() {
        VatUpdate update = mock(VatUpdate.class);
        when(keycloakSecurityHelper.canUserAccessOrg(orgId)).thenReturn(false);

        ResponseEntity<?> response = vatController.updateReferenceCode(orgId, update);

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(vatService);
    }

    @Test
    void insertVatCodesCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        VatView view = mock(VatView.class);
        when(vatService.insertVatCodesCsv(orgId, file)).thenReturn(Either.right(List.of(view)));

        ResponseEntity<?> response = vatController.insertVatCodesCsv(orgId, file);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(view), response.getBody());
    }

    @Test
    void insertVatCodesCsv_error() {
        MultipartFile file = mock(MultipartFile.class);
        Either<ProblemDetail, List<VatView>> either = Either.left(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST));
        when(vatService.insertVatCodesCsv(orgId, file)).thenReturn(either);

        ResponseEntity<?> response = vatController.insertVatCodesCsv(orgId, file);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(either.getLeft(), response.getBody());
    }

    @Test
    void insertVatCodesCsv_noOrgAccess() {
        MultipartFile file = mock(MultipartFile.class);
        when(keycloakSecurityHelper.canUserAccessOrg(orgId)).thenReturn(false);

        ResponseEntity<?> response = vatController.insertVatCodesCsv(orgId, file);

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(vatService);
    }
}
