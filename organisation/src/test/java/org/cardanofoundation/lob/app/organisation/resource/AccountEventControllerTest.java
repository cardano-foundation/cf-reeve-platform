package org.cardanofoundation.lob.app.organisation.resource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.*;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.organisation.domain.request.EventCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.AccountEventView;
import org.cardanofoundation.lob.app.organisation.service.AccountEventService;
import org.cardanofoundation.lob.app.organisation.service.OrganisationService;

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
    void insertReferenceCodeByCsv_error() {
        when(accountEventService.insertAccountEventByCsv("orgId", null)).thenReturn(Either.left(List.of(Problem.builder()
                .withTitle("Error")
                .withStatus(Status.BAD_REQUEST)
                .build())));

        ResponseEntity<?> response = controller.insertReferenceCodeByCsv("orgId", null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(List.class);
        assertThat(((List<?>) response.getBody())).hasSize(1);
    }

    @Test
    void insertReferenceCodeByCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        AccountEventView view = mock(AccountEventView.class);
        when(accountEventService.insertAccountEventByCsv(orgId, file)).thenReturn(Either.right(
                List.of(view)));

        ResponseEntity<?> response = controller.insertReferenceCodeByCsv(orgId, file);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(List.class);
        assertThat(((List<?>) response.getBody())).hasSize(1);
        assertThat(((List<?>) response.getBody()).iterator().next()).isEqualTo(view);
    }

    @Test
    void testGetReferenceCodes_returnsOk() {
        when(accountEventService.getAllAccountEvent(orgId, null, null, null, null, null, Pageable.unpaged())).thenReturn(Either.right(List.of()));

        ResponseEntity<?> response = controller.getReferenceCodes(orgId, null, null, null, null, null, Pageable.unpaged());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(accountEventService).getAllAccountEvent(orgId, null, null, null, null, null, Pageable.unpaged());
    }

    @Test
    void testInsertReferenceCode_returnsOk() {
        EventCodeUpdate update = new EventCodeUpdate();
        AccountEventView view = mock(AccountEventView.class);
        when(accountEventService.insertAccountEvent(orgId, update, false)).thenReturn(view);
        when(view.getError()).thenReturn(Optional.empty());
        ResponseEntity<?> response = controller.insertReferenceCode(orgId, update);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
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
        when(accountEventService.insertAccountEvent(orgId, update, false)).thenReturn(view);

        ResponseEntity<?> response = controller.insertReferenceCode(orgId, update);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void testUpdateReferenceCode_returnsOk() {
        EventCodeUpdate update = new EventCodeUpdate();
        AccountEventView view = mock(AccountEventView.class);
        when(accountEventService.updateAccountEvent(orgId, update)).thenReturn(view);

        ResponseEntity<?> response = controller.updateReferenceCode(orgId, update);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
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

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(view);
    }

}
