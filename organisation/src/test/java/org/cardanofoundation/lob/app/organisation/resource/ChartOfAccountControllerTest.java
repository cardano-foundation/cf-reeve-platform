package org.cardanofoundation.lob.app.organisation.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.*;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.*;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.organisation.domain.request.ChartOfAccountUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.*;
import org.cardanofoundation.lob.app.organisation.service.ChartOfAccountsService;

class ChartOfAccountControllerTest {

    @Mock
    private ChartOfAccountsService chartOfAccountsService;

    @InjectMocks
    private ChartOfAccountController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void insertReferenceCodeByCsv_error() {
        when(chartOfAccountsService.insertChartOfAccountByCsv("orgId", null)).thenReturn(Either.left(
                List.of(Problem.builder()
                .withTitle("Error")
                .withStatus(Status.BAD_REQUEST)
                .build())));

        ResponseEntity<?> response = controller.insertChartOfAccountByCsv("orgId", null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(List.class);
        assertThat(((List<?>) response.getBody())).hasSize(1);
    }

    @Test
    void insertReferenceCodeByCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        ChartOfAccountView view = mock(ChartOfAccountView.class);
        when(chartOfAccountsService.insertChartOfAccountByCsv("orgId", file)).thenReturn(Either.right(
                List.of(view)));

        ResponseEntity<?> response = controller.insertChartOfAccountByCsv("orgId", file);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(List.class);
        assertThat(((List<?>) response.getBody())).hasSize(1);
        assertThat(((List<?>) response.getBody()).iterator().next()).isEqualTo(view);
    }

    @Test
    void getChartOfAccounts_returnsAccounts() {
        String orgId = "org-1";
        List<ChartOfAccountView> accounts = List.of(mock(ChartOfAccountView.class));
        when(chartOfAccountsService.getAllChartOfAccount(orgId, null, null, null, null, null, null,
                null, null, Pageable.unpaged())).thenReturn(Either.right(accounts));

        ResponseEntity<?> response = controller.getChartOfAccounts(orgId,  null, null, null, null, null, null, null, null, Pageable.unpaged());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(accounts);
        verify(chartOfAccountsService).getAllChartOfAccount(orgId,  null, null, null, null, null,
                null, null, null, Pageable.unpaged());
    }

    @Test
    void insertChartOfAccount_success() {
        String orgId = "org-1";
        ChartOfAccountUpdate update = mock(ChartOfAccountUpdate.class);
        ChartOfAccountView view = mock(ChartOfAccountView.class);
        when(view.getError()).thenReturn(Optional.empty());
        when(chartOfAccountsService.insertChartOfAccount(orgId, update, false)).thenReturn(view);

        ResponseEntity<?> response = controller.insertChartOfAccount(orgId, update);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void insertChartOfAccount_withError() {
        String orgId = "org-1";
        ChartOfAccountUpdate update = mock(ChartOfAccountUpdate.class);
        ChartOfAccountView view = mock(ChartOfAccountView.class);

        when(view.getError()).thenReturn(Optional.of(Problem.builder()
                .withTitle("Error")
                .withDetail("Invalid")
                .withStatus(Status.BAD_REQUEST)
                .build()));
        when(chartOfAccountsService.insertChartOfAccount(orgId, update, false)).thenReturn(view);

        ResponseEntity<?> response = controller.insertChartOfAccount(orgId, update);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void updateChartOfAccount_success() {
        String orgId = "org-1";
        ChartOfAccountUpdate update = mock(ChartOfAccountUpdate.class);
        ChartOfAccountView view = mock(ChartOfAccountView.class);
        when(view.getError()).thenReturn(Optional.empty());
        when(chartOfAccountsService.updateChartOfAccount(orgId, update)).thenReturn(view);

        ResponseEntity<?> response = controller.updateChartOfAccount(orgId, update);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(view);
    }

}
