package org.cardanofoundation.lob.app.organisation.resource;

import org.cardanofoundation.lob.app.organisation.domain.request.ChartOfAccountUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.*;
import org.cardanofoundation.lob.app.organisation.service.AccountEventService;
import org.cardanofoundation.lob.app.organisation.service.ChartOfAccountsService;
import org.cardanofoundation.lob.app.organisation.service.OrganisationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.http.ResponseEntity;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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
    void getChartOfAccounts_returnsAccounts() {
        String orgId = "org-1";
        Set<OrganisationChartOfAccountView> accounts = Set.of(mock(OrganisationChartOfAccountView.class));
        when(chartOfAccountsService.getAllChartOfAccount(orgId)).thenReturn(accounts);

        ResponseEntity<Set<OrganisationChartOfAccountView>> response = controller.getChartOfAccounts(orgId);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(accounts);
        verify(chartOfAccountsService).getAllChartOfAccount(orgId);
    }

    @Test
    void insertChartOfAccount_success() {
        String orgId = "org-1";
        ChartOfAccountUpdate update = mock(ChartOfAccountUpdate.class);
        OrganisationChartOfAccountView view = mock(OrganisationChartOfAccountView.class);
        when(view.getError()).thenReturn(Optional.empty());
        when(chartOfAccountsService.insertChartOfAccount(orgId, update)).thenReturn(view);

        ResponseEntity<?> response = controller.insertChartOfAccount(orgId, update);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void insertChartOfAccount_withError() {
        String orgId = "org-1";
        ChartOfAccountUpdate update = mock(ChartOfAccountUpdate.class);
        OrganisationChartOfAccountView view = mock(OrganisationChartOfAccountView.class);

        when(view.getError()).thenReturn(Optional.of(Problem.builder()
                .withTitle("Error")
                .withDetail("Invalid")
                .withStatus(Status.BAD_REQUEST)
                .build()));
        when(chartOfAccountsService.insertChartOfAccount(orgId, update)).thenReturn(view);

        ResponseEntity<?> response = controller.insertChartOfAccount(orgId, update);

        assertThat(response.getStatusCodeValue()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void updateChartOfAccount_success() {
        String orgId = "org-1";
        ChartOfAccountUpdate update = mock(ChartOfAccountUpdate.class);
        OrganisationChartOfAccountView view = mock(OrganisationChartOfAccountView.class);
        when(view.getError()).thenReturn(Optional.empty());
        when(chartOfAccountsService.updateChartOfAccount(orgId, update)).thenReturn(view);

        ResponseEntity<?> response = controller.updateChartOfAccount(orgId, update);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    void upsertChartOfAccount_withError() {
        String orgId = "org-1";
        ChartOfAccountUpdate update = mock(ChartOfAccountUpdate.class);
        OrganisationChartOfAccountView view = mock(OrganisationChartOfAccountView.class);

        when(view.getError()).thenReturn(Optional.of(Problem.builder()
                .withTitle("Error")
                .withDetail("Invalid")
                .withStatus(Status.BAD_REQUEST)
                .build()));
        when(chartOfAccountsService.upsertChartOfAccount(orgId, update)).thenReturn(view);

        ResponseEntity<?> response = controller.upsertChartOfAccount(orgId, update);

        assertThat(response.getStatusCodeValue()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(view);
    }

}
