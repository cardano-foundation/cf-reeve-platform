package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.AccountingCorePresentationViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReconcileResponseView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReconciliationResponseView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.AccountingCoreService;

@ExtendWith(MockitoExtension.class)
class AccountingCoreResourceReconcilationTest {

    @Mock
    private AccountingCorePresentationViewService accountingCorePresentationService;
    @Mock
    private AccountingCoreService accountingCoreService;

    @InjectMocks
    private AccountingCoreResourceReconciliation accountingCoreResourceReconciliation;

    @Test
    void testReconciliationTrigger_shouldReturnProblem() {
        when(accountingCoreService.scheduleReconcilation("orgId", LocalDate.now(),LocalDate.now(), ExtractorType.NETSUITE, Optional.empty(), Map.of()))
                .thenReturn(Either.left(Problem.builder().withStatus(Status.BAD_REQUEST).build()));
        ReconciliationRequest request = mock(ReconciliationRequest.class);
        when(request.getOrganisationId()).thenReturn("orgId");
        when(request.getDateFrom()).thenReturn(LocalDate.now());
        when(request.getDateTo()).thenReturn(LocalDate.now());
        when(request.getExtractorType()).thenReturn(ExtractorType.NETSUITE);
        when(request.getFile()).thenReturn(null);
        when(request.getParameters()).thenReturn(Map.of());
        ResponseEntity<ReconcileResponseView> reconcileResponseViewResponseEntity = accountingCoreResourceReconciliation.reconcileTriggerAction(request);
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), reconcileResponseViewResponseEntity.getStatusCode().value());

        verify(accountingCoreService).scheduleReconcilation("orgId", LocalDate.now(),LocalDate.now(), ExtractorType.NETSUITE, Optional.empty(), Map.of());
        verifyNoMoreInteractions(accountingCoreService);
        verifyNoInteractions(accountingCorePresentationService);

    }

    @Test
    void testReconciliationTrigger_successFull() {
        when(accountingCoreService.scheduleReconcilation("orgId", LocalDate.now(),LocalDate.now(), ExtractorType.NETSUITE, Optional.empty(), Map.of()))
                .thenReturn(Either.right(null));
        ReconciliationRequest request = mock(ReconciliationRequest.class);
        when(request.getOrganisationId()).thenReturn("orgId");
        when(request.getDateFrom()).thenReturn(LocalDate.now());
        when(request.getDateTo()).thenReturn(LocalDate.now());
        when(request.getExtractorType()).thenReturn(ExtractorType.NETSUITE);
        when(request.getFile()).thenReturn(null);
        when(request.getParameters()).thenReturn(Map.of());
        ResponseEntity<ReconcileResponseView> reconcileResponseViewResponseEntity = accountingCoreResourceReconciliation.reconcileTriggerAction(request);
        Assertions.assertEquals(200, reconcileResponseViewResponseEntity.getStatusCode().value());

        verify(accountingCoreService).scheduleReconcilation("orgId", LocalDate.now(),LocalDate.now(), ExtractorType.NETSUITE, Optional.empty(), Map.of());
        verifyNoMoreInteractions(accountingCoreService);
        verifyNoInteractions(accountingCorePresentationService);
    }

    @Test
    void testReconcileStart() {
        ReconciliationResponseView responseView = new ReconciliationResponseView(5L, Optional.of(LocalDate.now()), Optional.of(LocalDate.now()), Optional.of(LocalDate.now()), null, null);
        when(accountingCorePresentationService.allReconciliationTransaction(any(ReconciliationFilterRequest.class), any(Pageable.class)))
                .thenReturn(responseView);
        when(accountingCorePresentationService.convertPageable(any(Pageable.class), any())).thenReturn(Either.right(Pageable.unpaged()));

        ReconciliationFilterRequest request = mock(ReconciliationFilterRequest.class);
        ResponseEntity<?> response = accountingCoreResourceReconciliation.reconcileStart(request, Pageable.unpaged());
        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertEquals(responseView, response.getBody());
    }
}
