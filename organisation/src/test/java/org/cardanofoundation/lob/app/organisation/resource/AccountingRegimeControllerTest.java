package org.cardanofoundation.lob.app.organisation.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

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

import org.cardanofoundation.lob.app.organisation.domain.request.StandardUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.StandardView;
import org.cardanofoundation.lob.app.organisation.service.StandardService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class StandardControllerTest {

    @Mock
    private StandardService standardService;

    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @InjectMocks
    private StandardController standardController;

    @BeforeEach
    void setUp() {
        lenient().when(keycloakSecurityHelper.canUserAccessOrg(any())).thenReturn(true);
    }

    @Test
    void getAllStandards_noOrgAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        ResponseEntity<List<StandardView>> response = standardController.getAllStandards("org123");

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(standardService);
    }

    @Test
    void insertStandard_noOrgAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        ResponseEntity<StandardView> response = standardController.insertStandard("org123", mock(StandardUpdate.class));

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(standardService);
    }

    @Test
    void updateStandard_noOrgAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        ResponseEntity<StandardView> response = standardController.updateStandard("org123", mock(StandardUpdate.class));

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(standardService);
    }

    @Test
    void insertStandardsCsv_noOrgAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg("org123")).thenReturn(false);

        ResponseEntity<Object> response = standardController.insertStandardsCsv("org123", mock(MultipartFile.class));

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(standardService);
    }

    @Test
    void getAllStandards_success() {
        StandardView view = StandardView.createSuccess("IFRS", "IFRS Label", true);
        when(standardService.getAllStandards("org123")).thenReturn(List.of(view));

        ResponseEntity<List<StandardView>> response = standardController.getAllStandards("org123");

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("IFRS", response.getBody().get(0).getCode());
    }

    @Test
    void insertStandard_success() {
        StandardUpdate update = mock(StandardUpdate.class);
        StandardView view = StandardView.createSuccess("IFRS", "IFRS Label", true);
        when(standardService.insertStandard("org123", update, false)).thenReturn(view);

        ResponseEntity<StandardView> response = standardController.insertStandard("org123", update);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals("IFRS", response.getBody().getCode());
    }

    @Test
    void insertStandard_error() {
        StandardUpdate update = mock(StandardUpdate.class);
        ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Standard with code IFRS already exists");
        StandardView view = StandardView.createFail(error, new StandardUpdate("IFRS", "IFRS Label", true));
        when(standardService.insertStandard("org123", update, false)).thenReturn(view);

        ResponseEntity<StandardView> response = standardController.insertStandard("org123", update);

        assertEquals(HttpStatus.CONFLICT.value(), response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getError().isPresent());
    }

    @Test
    void updateStandard_success() {
        StandardUpdate update = mock(StandardUpdate.class);
        StandardView view = StandardView.createSuccess("IFRS", "Updated Label", false);
        when(standardService.updateStandard("org123", update)).thenReturn(view);

        ResponseEntity<StandardView> response = standardController.updateStandard("org123", update);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals("Updated Label", response.getBody().getLabel());
    }

    @Test
    void updateStandard_error() {
        StandardUpdate update = mock(StandardUpdate.class);
        ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Standard with code IFRS not found");
        StandardView view = StandardView.createFail(error, new StandardUpdate("IFRS", "IFRS Label", true));
        when(standardService.updateStandard("org123", update)).thenReturn(view);

        ResponseEntity<StandardView> response = standardController.updateStandard("org123", update);

        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getError().isPresent());
    }

    @Test
    void insertStandardsCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        List<StandardView> views = List.of(StandardView.createSuccess("IFRS", "IFRS Label", true));
        when(standardService.insertViaCsv("org123", file)).thenReturn(Either.right(views));

        ResponseEntity<Object> response = standardController.insertStandardsCsv("org123", file);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertEquals(views, response.getBody());
    }

    @Test
    void insertStandardsCsv_problem() {
        MultipartFile file = mock(MultipartFile.class);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "CSV parse error");
        when(standardService.insertViaCsv("org123", file)).thenReturn(Either.left(problem));

        ResponseEntity<Object> response = standardController.insertStandardsCsv("org123", file);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
        assertEquals(problem, response.getBody());
    }

}
