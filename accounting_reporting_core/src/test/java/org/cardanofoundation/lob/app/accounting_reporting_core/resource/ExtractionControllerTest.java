package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.http.ResponseEntity;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.ExtractionItemService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ExtractionTransactionsRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ExtractionTransactionView;

@ExtendWith(MockitoExtension.class)
class ExtractionControllerTest {

    @Mock
    private ExtractionItemService extractionItemService;

    @InjectMocks
    private ExtractionController extractionController;

    @Test
    void transactionSearch_error() {
        ExtractionTransactionsRequest request = mock(ExtractionTransactionsRequest.class);
        when(request.getDateFrom()).thenReturn("2023-01-01");
        when(request.getDateTo()).thenReturn("2023-12-31");
        when(extractionItemService.findTransactionItems(any(),any(),any(), any(), any(), any(), any())).thenThrow(new RuntimeException());

        ResponseEntity<ExtractionTransactionView> extractionTransactionViewResponseEntity = extractionController.transactionSearch(request);
        assertEquals(500, extractionTransactionViewResponseEntity.getStatusCodeValue());
        assertNull(extractionTransactionViewResponseEntity.getBody());
    }

    @Test
    void transactionSearch_success() {
        ExtractionTransactionsRequest request = mock(ExtractionTransactionsRequest.class);
        when(request.getDateFrom()).thenReturn("2023-01-01");
        when(request.getDateTo()).thenReturn("2023-12-31");
        ExtractionTransactionView expectedResponse = mock(ExtractionTransactionView.class);

        when(extractionItemService.findTransactionItems(any(),any(),any(), any(), any(), any(), any())).thenReturn(expectedResponse);

        ResponseEntity<ExtractionTransactionView> extractionTransactionViewResponseEntity = extractionController.transactionSearch(request);
        assertEquals(200, extractionTransactionViewResponseEntity.getStatusCodeValue());
        assertEquals(expectedResponse, extractionTransactionViewResponseEntity.getBody());
    }
}
