package org.cardanofoundation.lob.app.organisation.resource;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.request.EventCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.AccountEventView;
import org.cardanofoundation.lob.app.organisation.service.AccountEventService;
import org.cardanofoundation.lob.app.organisation.service.OrganisationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.http.ResponseEntity;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

class AccountEventControllerTest {

    @Mock
    private AccountEventService accountEventService;

    @Mock
    private OrganisationService organisationService;

    @InjectMocks
    private AccountEventController controller;

    private final String orgId = "some-org-id";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testGetReferenceCodes_returnsOk() {
        when(accountEventService.getAllAccountEvent(orgId)).thenReturn(List.of());

        ResponseEntity<?> response = controller.getReferenceCodes(orgId);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(accountEventService).getAllAccountEvent(orgId);
    }

    @Test
    void testInsertReferenceCode_returnsOk() {
        EventCodeUpdate update = new EventCodeUpdate();
        AccountEventView view = mock(AccountEventView.class);
        when(accountEventService.insertAccountEvent(orgId, update)).thenReturn(view);
        when(view.getError()).thenReturn(Optional.empty());
        ResponseEntity<?> response = controller.insertReferenceCode(orgId, update);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void testInsertReferenceCode_withError() {
        EventCodeUpdate update = new EventCodeUpdate();
        AccountEventView view = mock(AccountEventView.class);
        when(view.getError()).thenReturn(Optional.of(Problem.builder()
                .withTitle("Error")
                .withStatus(Status.NOT_FOUND)
                .build()));
        when(accountEventService.insertAccountEvent(orgId, update)).thenReturn(view);

        ResponseEntity<?> response = controller.insertReferenceCode(orgId, update);

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void testUpdateReferenceCode_returnsOk() {
        EventCodeUpdate update = new EventCodeUpdate();
        AccountEventView view = mock(AccountEventView.class);
        when(accountEventService.updateAccountEvent(orgId, update)).thenReturn(view);

        ResponseEntity<?> response = controller.updateReferenceCode(orgId, update);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void testUpdateReferenceCode_withError() {
        EventCodeUpdate update = new EventCodeUpdate();
        AccountEventView view = mock(AccountEventView.class);
        when(view.getError()).thenReturn(Optional.of(Problem.builder()
                .withTitle("Error")
                .withStatus(Status.BAD_REQUEST)
                .build()));
        when(accountEventService.updateAccountEvent(orgId, update)).thenReturn(view);

        ResponseEntity<?> response = controller.updateReferenceCode(orgId, update);

        assertThat(response.getStatusCodeValue()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void testUpsertReferenceCode_returnsOk() {
        EventCodeUpdate update = new EventCodeUpdate();
        AccountEventView view = mock(AccountEventView.class);
        when(accountEventService.upsertAccountEvent(orgId, update)).thenReturn(view);

        ResponseEntity<?> response = controller.upsertReferenceCode(orgId, update);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void testUpsertReferenceCode_withError() {
        EventCodeUpdate update = new EventCodeUpdate();
        AccountEventView view = mock(AccountEventView.class);
        when(view.getError()).thenReturn(Optional.of(Problem.builder()
                .withTitle("Error")
                .withStatus(Status.INTERNAL_SERVER_ERROR)
                .build()));
        when(accountEventService.upsertAccountEvent(orgId, update)).thenReturn(view);

        ResponseEntity<?> response = controller.upsertReferenceCode(orgId, update);

        assertThat(response.getStatusCodeValue()).isEqualTo(500);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void testDeleteReferenceCode_orgNotFound() {
        when(organisationService.findById(orgId)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deleteReferenceCode(orgId, "ref123");

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
        assertThat(response.getBody()).isInstanceOf(Problem.class);
        assertThat(((Problem) response.getBody()).getTitle()).isEqualTo("ORGANISATION_NOT_FOUND");
    }

}
