package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.zalando.problem.Status.BAD_REQUEST;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.RejectionReason;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.AccountingCorePresentationViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.BatchSearchRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ExtractionRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.SearchRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.TransactionItemsRejectionRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.TransactionsRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchReprocessView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchsDetailView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.TransactionItemsProcessRejectView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.TransactionProcessView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.TransactionView;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@ExtendWith(MockitoExtension.class)
class AccountingCoreResourceTest {

    @Mock
    private AccountingCorePresentationViewService accountingCorePresentationViewService;
    @Mock
    private OrganisationPublicApi organisationPublicApi;

    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AccountingCoreResource accountingCoreResource;

    @BeforeEach
    void setUp() {
        accountingCoreResource = new AccountingCoreResource(accountingCorePresentationViewService, organisationPublicApi, objectMapper);
    }

    @Test
    void testListAllAction() {
        SearchRequest body = mock(SearchRequest.class);

        when(accountingCorePresentationViewService.allTransactions(body)).thenReturn(List.of());

        ResponseEntity<List<TransactionView>> listResponseEntity = accountingCoreResource.listAllAction(body);
        assertTrue(listResponseEntity.getStatusCode().is2xxSuccessful());
        assertNotNull(listResponseEntity.getBody());
    }

    @Test
    void transactionDetailSpecific_error() {
        String id = "1234567890";
        when(accountingCorePresentationViewService.transactionDetailSpecific(id)).thenReturn(Optional.empty());

        ResponseEntity<?> responseEntity = accountingCoreResource.transactionDetailSpecific(id);
        assertTrue(responseEntity.getStatusCode().is4xxClientError());
        assertNotNull(responseEntity.getBody());
    }

    @Test
    void transactionDetailSpecific_success() {
        String id = "1234567890";
        TransactionView transactionView = mock(TransactionView.class);
        when(accountingCorePresentationViewService.transactionDetailSpecific(id)).thenReturn(Optional.of(transactionView));

        ResponseEntity<?> responseEntity = accountingCoreResource.transactionDetailSpecific(id);
        assertTrue(responseEntity.getStatusCode().is2xxSuccessful());
        assertNotNull(responseEntity.getBody());
    }

    @Test
    void transactionType_test() throws JsonProcessingException {

        ResponseEntity<String> responseEntity = accountingCoreResource.transactionType();

        assertTrue(responseEntity.getStatusCode().is2xxSuccessful());
        assertNotNull(responseEntity.getBody());
    }

    @Test
    void rejectionReasons_test() {
        ResponseEntity<RejectionReason[]> responseEntity = accountingCoreResource.rejectionReasons();

        assertTrue(responseEntity.getStatusCode().is2xxSuccessful());
        assertNotNull(responseEntity.getBody());

        RejectionReason[] rejectionReasons = responseEntity.getBody();
        assertEquals(RejectionReason.values().length, rejectionReasons.length);
    }

    @Test
    void extractionTrigger_organisationNotFound() {
        ExtractionRequest request = mock(ExtractionRequest.class);

        when(request.getOrganisationId()).thenReturn("123");
        when(organisationPublicApi.findByOrganisationId(request.getOrganisationId())).thenReturn(Optional.empty());

        ResponseEntity<?> responseEntity = accountingCoreResource.extractionTrigger(request);
        assertTrue(responseEntity.getStatusCode().is4xxClientError());
        assertNotNull(responseEntity.getBody());

    }

    @Test
    void extractionTrigger_error() {
        ExtractionRequest request = mock(ExtractionRequest.class);
        Organisation org = mock(Organisation.class);
        Problem problem = Problem.builder()
                .withTitle("Test Problem")
                .withDetail("Test Problem")
                .withStatus(BAD_REQUEST)
                .build();

        when(request.getOrganisationId()).thenReturn("123");
        when(organisationPublicApi.findByOrganisationId(request.getOrganisationId())).thenReturn(Optional.of(org));
        when(accountingCorePresentationViewService.extractionTrigger(request)).thenReturn(Either.left(problem));

        ResponseEntity<?> responseEntity = accountingCoreResource.extractionTrigger(request);
        assertTrue(responseEntity.getStatusCode().is4xxClientError());
        assertNotNull(responseEntity.getBody());
        assertEquals(problem.getStatus(), ((Problem) responseEntity.getBody()).getStatus());
    }

    @Test
    void extractionTrigger_success() {
        ExtractionRequest request = mock(ExtractionRequest.class);
        Organisation org = mock(Organisation.class);

        when(request.getOrganisationId()).thenReturn("123");
        when(organisationPublicApi.findByOrganisationId(request.getOrganisationId())).thenReturn(Optional.of(org));
        when(accountingCorePresentationViewService.extractionTrigger(request)).thenReturn(Either.right(null));

        ResponseEntity<?> responseEntity = accountingCoreResource.extractionTrigger(request);
        assertTrue(responseEntity.getStatusCode().is2xxSuccessful());
        assertNotNull(responseEntity.getBody());
    }

    @Test
    void approveTransactions_test() {
        TransactionsRequest request = mock(TransactionsRequest.class);
        when(accountingCorePresentationViewService.approveTransactions(request)).thenReturn(List.of());

        ResponseEntity<List<TransactionProcessView>> listResponseEntity = accountingCoreResource.approveTransactions(request);

        assertTrue(listResponseEntity.getStatusCode().is2xxSuccessful());
        assertNotNull(listResponseEntity.getBody());
    }

    @Test
    void publishTransactions_test() {
        TransactionsRequest request = mock(TransactionsRequest.class);
        when(accountingCorePresentationViewService.approveTransactionsPublish(request)).thenReturn(List.of());

        ResponseEntity<List<TransactionProcessView>> listResponseEntity = accountingCoreResource.approveTransactionsPublish(request);

        assertTrue(listResponseEntity.getStatusCode().is2xxSuccessful());
        assertNotNull(listResponseEntity.getBody());
    }

    @Test
    void rejectTransactions_test() {
        TransactionItemsRejectionRequest request = mock(TransactionItemsRejectionRequest.class);
        TransactionItemsProcessRejectView transactionItemsProcessRejectView = mock(TransactionItemsProcessRejectView.class);
        when(accountingCorePresentationViewService.rejectTransactionItems(request)).thenReturn(transactionItemsProcessRejectView);

        ResponseEntity<TransactionItemsProcessRejectView> response = accountingCoreResource.rejectTransactionItems(request);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
    }

    @Test
    void listAllBatches_test() {
        BatchSearchRequest body = mock(BatchSearchRequest.class);
        BatchsDetailView batchsDetailView = mock(BatchsDetailView.class);

        when(accountingCorePresentationViewService.listAllBatch(body)).thenReturn(batchsDetailView);

        ResponseEntity<BatchsDetailView> listResponseEntity = accountingCoreResource.listAllBatches(body, 0, 0);
        assertTrue(listResponseEntity.getStatusCode().is2xxSuccessful());
        assertNotNull(listResponseEntity.getBody());
    }

    @Test
    void batchReprocess_test() {
        BatchReprocessView batchReprocessView = mock(BatchReprocessView.class);

        when(accountingCorePresentationViewService.scheduleReIngestionForFailed("123")).thenReturn(batchReprocessView);

        ResponseEntity<BatchReprocessView> batchReprocessViewResponseEntity = accountingCoreResource.batchReprocess("123");
        assertTrue(batchReprocessViewResponseEntity.getStatusCode().is2xxSuccessful());
        assertNotNull(batchReprocessViewResponseEntity.getBody());
    }

    @Test
    void batchesDetailTest_error() {
        when(accountingCorePresentationViewService.batchDetail("123", List.of(), Pageable.unpaged())).thenReturn(Optional.empty());

        ResponseEntity<?> responseEntity = accountingCoreResource.batchesDetail("123", Optional.empty(), Optional.empty(), List.of());
        assertTrue(responseEntity.getStatusCode().is4xxClientError());
        assertNotNull(responseEntity.getBody());
    }

    @Test
    void batchesDetailTest_success() {
        BatchView mock = mock(BatchView.class);
        when(accountingCorePresentationViewService.batchDetail("123", List.of(), Pageable.unpaged())).thenReturn(Optional.of(mock));

        ResponseEntity<?> responseEntity = accountingCoreResource.batchesDetail("123", Optional.empty(), Optional.empty(), List.of());
        assertTrue(responseEntity.getStatusCode().is2xxSuccessful());
        assertNotNull(responseEntity.getBody());
    }
}
