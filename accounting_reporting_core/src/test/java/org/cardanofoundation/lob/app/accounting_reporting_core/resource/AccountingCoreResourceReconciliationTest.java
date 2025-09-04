package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.zalando.problem.Status.BAD_REQUEST;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.AccountingCorePresentationViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationRejectionCodeRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.AccountingCoreService;

@ExtendWith(MockitoExtension.class)
class AccountingCoreResourceReconciliationTest {

    @Mock
    private AccountingCorePresentationViewService accountingCorePresentationViewService;
    @Mock
    private AccountingCoreService accountingCoreService;

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
        when(accountingCoreService.scheduleReconcilation(any(), any(), any(), any(), any(), any())).thenReturn(Either.left(Problem.builder()
                .withTitle("title")
                .withStatus(BAD_REQUEST)
                .build()));
        ResponseEntity<?> responseEntity = accountingCoreResourceReconciliation.reconcileTriggerAction(new ReconciliationRequest());

        Assertions.assertEquals(400, responseEntity.getStatusCode().value());
        verify(accountingCoreService).scheduleReconcilation(any(), any(), any(), any(), any(), any());
        verifyNoMoreInteractions(accountingCoreService);
        verifyNoInteractions(accountingCorePresentationViewService);
    }

    @Test
    void testReconcileStart() {
        when(accountingCorePresentationViewService.allReconciliationTransaction(any(), any())).thenReturn(null);
        when(accountingCorePresentationViewService.convertPageable(any(Pageable.class), any())).thenReturn(Either.right(Pageable.unpaged()));
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
}
