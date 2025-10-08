package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.zalando.problem.Status.BAD_REQUEST;

import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.AccountingCorePresentationViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.ExtractionItemService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ExtractionRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ExtractionTransactionsRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ExtractionTransactionView;
import org.cardanofoundation.lob.app.accounting_reporting_core.utils.SortFieldMappings;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@ExtendWith(MockitoExtension.class)
class ExtractionControllerTest {

    @Mock
    private ExtractionItemService extractionItemService;
    @Mock
    private OrganisationPublicApi organisationPublicApi;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private AccountingCorePresentationViewService accountingCorePresentationViewService;
    @Mock
    private SortFieldMappings sortFieldMappings;

    @InjectMocks
    private ExtractionController extractionController;

    @BeforeEach
    void setUp() {
        extractionController = new ExtractionController(extractionItemService, organisationPublicApi, accountingCorePresentationViewService, objectMapper, sortFieldMappings);
    }

    @Test
    void transactionSearch_error() {
        ExtractionTransactionsRequest request = mock(ExtractionTransactionsRequest.class);
        when(sortFieldMappings.convertPageable(any(Pageable.class), any(),
                eq(TransactionItemEntity.class))).thenReturn(Either.right(Pageable.unpaged()));
        when(extractionItemService.findTransactionItems(any(),any(),any(), any(), any(), any(), any())).thenThrow(new RuntimeException());

        ResponseEntity<?> extractionTransactionViewResponseEntity = extractionController.transactionSearch(request,
                Pageable.unpaged());
        assertEquals(500, extractionTransactionViewResponseEntity.getStatusCode().value());
        assertNull(extractionTransactionViewResponseEntity.getBody());
    }

    @Test
    void transactionSearch_success() {
        ExtractionTransactionsRequest request = mock(ExtractionTransactionsRequest.class);
        ExtractionTransactionView expectedResponse = mock(ExtractionTransactionView.class);
        when(sortFieldMappings.convertPageable(any(Pageable.class), any(), eq(TransactionItemEntity.class)))
                .thenReturn(Either.right(Pageable.unpaged()));
        when(extractionItemService.findTransactionItems(request, Pageable.unpaged())).thenReturn(expectedResponse);

        ResponseEntity<?> extractionTransactionViewResponseEntity = extractionController.transactionSearch(request, Pageable.unpaged());
        assertEquals(200, extractionTransactionViewResponseEntity.getStatusCode().value());
        assertEquals(expectedResponse, extractionTransactionViewResponseEntity.getBody());
    }


    @Test
    void extractionTrigger_organisationNotFound() {
        ExtractionRequest request = mock(ExtractionRequest.class);

        when(request.getOrganisationId()).thenReturn("123");
        when(organisationPublicApi.findByOrganisationId(request.getOrganisationId())).thenReturn(Optional.empty());

        ResponseEntity<?> responseEntity = extractionController.extractionTrigger(request);
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

        ResponseEntity<?> responseEntity = extractionController.extractionTrigger(request);
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

        ResponseEntity<?> responseEntity = extractionController.extractionTrigger(request);
        assertTrue(responseEntity.getStatusCode().is2xxSuccessful());
        assertNotNull(responseEntity.getBody());
    }
}
