package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.AccountingCorePresentationViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationRejectionCodeRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationStatisticRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReconciliationStatisticView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.AccountingCoreService;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;

@ExtendWith(MockitoExtension.class)
class AccountingCoreResourceReconciliationTest {

    @Mock
    private AccountingCorePresentationViewService accountingCorePresentationViewService;
    @Mock
    private AccountingCoreService accountingCoreService;
    @Mock
    private JpaSortFieldValidator jpaSortFieldValidator;

    @InjectMocks
    private AccountingCoreResourceReconciliation accountingCoreResourceReconciliation;

    @Test
    void testReconcileTriggerAction_successfull() {
        when(accountingCoreService.scheduleReconcilation(any(), any(), any(), any(), any(), any())).thenReturn(Either.right(null));
        ResponseEntity<?> responseEntity = accountingCoreResourceReconciliation.reconcileTriggerAction(new ReconciliationRequest());
        Assertions.assertEquals(200, responseEntity.getStatusCode().value());
        verify(accountingCoreService).scheduleReconcilation(any(), any(), any(), any(), any(), any());
        verifyNoMoreInteractions(accountingCoreService);
        verifyNoInteractions(accountingCorePresentationViewService);
    }

    @Test
    void testReconcileTriggerAction_problem() {
        when(accountingCoreService.scheduleReconcilation(any(), any(), any(), any(), any(), any())).thenReturn(Either.left(ProblemDetail.forStatus(BAD_REQUEST)));
        ResponseEntity<?> responseEntity = accountingCoreResourceReconciliation.reconcileTriggerAction(new ReconciliationRequest());

        Assertions.assertEquals(400, responseEntity.getStatusCode().value());
        verify(accountingCoreService).scheduleReconcilation(any(), any(), any(), any(), any(), any());
        verifyNoMoreInteractions(accountingCoreService);
        verifyNoInteractions(accountingCorePresentationViewService);
    }

    @Test
    void testReconcileStart() {
        when(accountingCorePresentationViewService.allReconciliationTransaction(any(), any())).thenReturn(null);
        when(jpaSortFieldValidator.convertPageable(any(Pageable.class), any(), eq(TransactionEntity.class))).thenReturn(Either.right(Pageable.unpaged()));
        ResponseEntity<?> responseEntity = accountingCoreResourceReconciliation.reconcileStart(new ReconciliationFilterRequest(), Pageable.unpaged());
        Assertions.assertEquals(200, responseEntity.getStatusCode().value());
        verify(accountingCorePresentationViewService).allReconciliationTransaction(any(), any());
        verifyNoMoreInteractions(accountingCorePresentationViewService);
        verifyNoInteractions(accountingCoreService);
    }

    @Test
    void testReconciliationRejectionCode() {
        ResponseEntity<?> responseEntity = accountingCoreResourceReconciliation.reconciliationRejectionCode();
        Assertions.assertEquals(200, responseEntity.getStatusCode().value());
        ReconciliationRejectionCodeRequest[] body = (ReconciliationRejectionCodeRequest[])responseEntity.getBody();
        Assertions.assertNotNull(body);
        Assertions.assertEquals(5, body.length);
        verifyNoInteractions(accountingCoreService);
        verifyNoInteractions(accountingCorePresentationViewService);
    }

    @Test
    void testReconciliationStatistic_successful() {
        Map<String, ReconciliationStatisticView> expected = new LinkedHashMap<>();
        expected.put("STATISTICS", new ReconciliationStatisticView(10L, 5L));
        when(accountingCorePresentationViewService.getReconciliationStatisticByDateRange(any(ReconciliationStatisticRequest.class))).thenReturn(expected);

        ReconciliationStatisticRequest request = new ReconciliationStatisticRequest();
        ResponseEntity<Map<String, ReconciliationStatisticView>> responseEntity = accountingCoreResourceReconciliation.reconciliationStatistic(request);

        Assertions.assertEquals(200, responseEntity.getStatusCode().value());
        Assertions.assertNotNull(responseEntity.getBody());
        Assertions.assertEquals(1, responseEntity.getBody().size());
        Assertions.assertTrue(responseEntity.getBody().containsKey("STATISTICS"));
        Assertions.assertEquals(10L, responseEntity.getBody().get("STATISTICS").getReconciledCount());
        Assertions.assertEquals(5L, responseEntity.getBody().get("STATISTICS").getUnreconciledCount());
        verify(accountingCorePresentationViewService).getReconciliationStatisticByDateRange(any(ReconciliationStatisticRequest.class));
        verifyNoMoreInteractions(accountingCorePresentationViewService);
        verifyNoInteractions(accountingCoreService);
    }

    @Test
    void testReconciliationStatistic_emptyResult() {
        Map<String, ReconciliationStatisticView> expected = new LinkedHashMap<>();
        expected.put("STATISTICS", new ReconciliationStatisticView(0L, 0L));
        when(accountingCorePresentationViewService.getReconciliationStatisticByDateRange(any(ReconciliationStatisticRequest.class))).thenReturn(expected);

        ReconciliationStatisticRequest request = new ReconciliationStatisticRequest();
        ResponseEntity<Map<String, ReconciliationStatisticView>> responseEntity = accountingCoreResourceReconciliation.reconciliationStatistic(request);

        Assertions.assertEquals(200, responseEntity.getStatusCode().value());
        Assertions.assertNotNull(responseEntity.getBody());
        Assertions.assertEquals(0L, responseEntity.getBody().get("STATISTICS").getReconciledCount());
        Assertions.assertEquals(0L, responseEntity.getBody().get("STATISTICS").getUnreconciledCount());
        verify(accountingCorePresentationViewService).getReconciliationStatisticByDateRange(any(ReconciliationStatisticRequest.class));
        verifyNoMoreInteractions(accountingCorePresentationViewService);
        verifyNoInteractions(accountingCoreService);
    }
}
