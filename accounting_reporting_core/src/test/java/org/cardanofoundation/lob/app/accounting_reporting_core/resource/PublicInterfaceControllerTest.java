package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.ResponseEntity;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.ExtractionItemService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.ReportViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.PublicInterfaceTransactionsRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ExtractionTransactionView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.ReportService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@ExtendWith(MockitoExtension.class)
class PublicInterfaceControllerTest {

    @Mock
    private ExtractionItemService extractionItemService;
    @Mock
    private ReportViewService reportViewService;
    @Mock
    private ReportService reportService;
    @Mock
    private OrganisationPublicApi organisationPublicApi;

    @InjectMocks
    private PublicInterfaceController publicInterfaceController;

    @Test
    void transactionSearchPublicInterfaceTest_OrgNotFound() {
        PublicInterfaceTransactionsRequest request = mock(PublicInterfaceTransactionsRequest.class);

        when(request.getOrganisationId()).thenReturn("org-id");

        when(organisationPublicApi.findByOrganisationId("org-id")).thenReturn(Optional.empty());

        ResponseEntity<ExtractionTransactionView> response = publicInterfaceController.transactionSearchPublicInterface(request);
        assertTrue(response.getStatusCode().is4xxClientError());
    }

    @Test
    void transactionSearchPublicInterfaceTest_success() {
        PublicInterfaceTransactionsRequest request = mock(PublicInterfaceTransactionsRequest.class);
        Organisation organisation = mock(Organisation.class);

        ExtractionTransactionView extractionTransactionView = mock(ExtractionTransactionView.class);

        when(request.getOrganisationId()).thenReturn("org-id");
        when(request.getDateFrom()).thenReturn(LocalDate.EPOCH);
        when(request.getDateTo()).thenReturn(LocalDate.EPOCH);
        when(request.getEvents()).thenReturn(Set.of());
        when(request.getCurrency()).thenReturn(Set.of());
        when(request.getMinAmount()).thenReturn(Optional.empty());
        when(request.getMaxAmount()).thenReturn(Optional.empty());
        when(request.getTransactionHashes()).thenReturn(Set.of());
        when(request.getPage()).thenReturn(0);
        when(request.getLimit()).thenReturn(10);
        when(organisationPublicApi.findByOrganisationId("org-id")).thenReturn(Optional.of(organisation));

        when(extractionItemService.findTransactionItemsPublic("org-id", LocalDate.EPOCH, LocalDate.EPOCH, Set.of(), Set.of(), Optional.empty(), Optional.empty(), Set.of(), 0, 10)).thenReturn(extractionTransactionView);

        ResponseEntity<ExtractionTransactionView> response = publicInterfaceController.transactionSearchPublicInterface(request);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertTrue(response.getBody() instanceof ExtractionTransactionView);
    }

}
