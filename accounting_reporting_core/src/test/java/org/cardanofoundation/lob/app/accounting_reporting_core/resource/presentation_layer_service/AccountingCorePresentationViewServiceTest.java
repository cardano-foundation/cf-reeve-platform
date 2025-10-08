package org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FilterOptions;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.ReconcilationRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepositoryGateway;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionReconcilationRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterStatusRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.response.FilteringOptionsListResponse;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReconciliationResponseView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.AccountingCoreService;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.TransactionRepositoryGateway;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.*;

@ExtendWith(MockitoExtension.class)
class AccountingCorePresentationViewServiceTest {

    @Mock
    private TransactionRepositoryGateway transactionRepositoryGateway;
    @Mock
    private AccountingCoreService accountingCoreService;
    @Mock
    private TransactionBatchRepositoryGateway transactionBatchRepositoryGateway;
    @Mock
    private TransactionReconcilationRepository transactionReconcilationRepository;
    @Mock
    private AccountingCoreTransactionRepository accountingCoreTransactionRepository;
    @Mock
    private TransactionItemRepository transactionItemRepository;
    @Mock
    private OrganisationPublicApiIF organisationPublicApiIF;
    @Mock
    private ReconcilationRepository reconcilationRepository;

    @InjectMocks
    private AccountingCorePresentationViewService accountingCorePresentationViewService;

    @Test
    void testAllReconiciliationTransaction_successfulUnprocessed() {
        when(reconcilationRepository.findCalcReconciliationStatistic()).thenReturn(new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
        when(transactionReconcilationRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        when(reconcilationRepository.findAllReconcilation(any(), eq(null), eq(null), eq(null),
                eq(null), eq(null), eq(Pageable.unpaged()))).thenReturn(Page.empty());
        ReconciliationFilterRequest body = mock(ReconciliationFilterRequest.class);
        when(body.getFilter()).thenReturn(ReconciliationFilterStatusRequest.UNPROCESSED);

        ReconciliationResponseView responseView = accountingCorePresentationViewService.allReconciliationTransaction(body, Pageable.unpaged());

        Assertions.assertEquals(0L, responseView.getTotal().longValue());
        Assertions.assertEquals(0, responseView.getStatistic().getMissingInERP());
        Assertions.assertEquals(1, responseView.getStatistic().getInProcessing());
        Assertions.assertEquals(2, responseView.getStatistic().getNewInERP());
        Assertions.assertEquals(3, responseView.getStatistic().getNewVersionNotPublished());
        Assertions.assertEquals(4, responseView.getStatistic().getNewVersion());
        Assertions.assertEquals(5L, responseView.getStatistic().getOK());
        Assertions.assertEquals(6, responseView.getStatistic().getNOK());
        Assertions.assertEquals(7L, responseView.getStatistic().getNEVER());
        Assertions.assertEquals(11, responseView.getStatistic().getTOTAL()); // Array index 5 + Array index 6
        Assertions.assertEquals(Optional.empty(), responseView.getLastDateFrom());
        Assertions.assertEquals(Optional.empty(), responseView.getLastDateTo());
        Assertions.assertEquals(Optional.empty(), responseView.getLastReconciledDate());


        verify(reconcilationRepository).findCalcReconciliationStatistic();
        verify(transactionReconcilationRepository).findTopByOrderByCreatedAtDesc();
        verifyNoMoreInteractions(accountingCoreTransactionRepository, transactionReconcilationRepository, transactionRepositoryGateway);
        verifyNoInteractions(accountingCoreService, transactionBatchRepositoryGateway);
    }

    @Test
    void testAllReconiciliationTransaction_successfulUnReconciled() {
        when(reconcilationRepository.findCalcReconciliationStatistic()).thenReturn(new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
        when(transactionReconcilationRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        when(reconcilationRepository.findAllReconciliationSpecial(any(), any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        ReconciliationFilterRequest body = mock(ReconciliationFilterRequest.class);
        when(body.getFilter()).thenReturn(ReconciliationFilterStatusRequest.UNRECONCILED);

        accountingCorePresentationViewService.allReconciliationTransaction(body, Pageable.unpaged());

        verify(reconcilationRepository).findCalcReconciliationStatistic();
        verify(transactionReconcilationRepository).findTopByOrderByCreatedAtDesc();
        verifyNoMoreInteractions(accountingCoreTransactionRepository, transactionReconcilationRepository);
        verifyNoInteractions(accountingCoreService, transactionBatchRepositoryGateway, transactionRepositoryGateway);
    }

    @Test
    void getFilterOptions_emptyList() {
        Map<FilterOptions, List<FilteringOptionsListResponse>> map = accountingCorePresentationViewService.getFilterOptions(List.of(), "org123");

        assertTrue(map.isEmpty());
        verifyNoInteractions(transactionBatchRepositoryGateway);
        verifyNoInteractions(transactionItemRepository);
    }

    @Test
    void getFilterOptions_transactionTypes() {
        Map<FilterOptions, List<FilteringOptionsListResponse>> map = accountingCorePresentationViewService.getFilterOptions(List.of(FilterOptions.TRANSACTION_TYPES), "org123");

        assertFalse(map.isEmpty());
        assertEquals(TransactionType.values().length, map.get(FilterOptions.TRANSACTION_TYPES).size());
        verifyNoInteractions(transactionBatchRepositoryGateway);
        verifyNoInteractions(transactionItemRepository);
    }

    @Test
    void getFilterOptions_users() {
        when(transactionBatchRepositoryGateway.findBatchUsersList("org123")).thenReturn(List.of("user1", "user2"));

        Map<FilterOptions, List<FilteringOptionsListResponse>> map = accountingCorePresentationViewService.getFilterOptions(List.of(FilterOptions.USERS), "org123");

        assertFalse(map.isEmpty());
        assertEquals(2, map.get(FilterOptions.USERS).size());
        verify(transactionBatchRepositoryGateway).findBatchUsersList("org123");
        verifyNoMoreInteractions(transactionBatchRepositoryGateway);
        verifyNoInteractions(transactionItemRepository);
    }

    @Test
    void getFilterOptions_AllOptions() {
        when(transactionBatchRepositoryGateway.findBatchUsersList("org123")).thenReturn(List.of("user1", "user2"));
        when(transactionItemRepository.getAllDocumentNumbers()).thenReturn(List.of("Doc12"));


        when(transactionItemRepository.getAllCounterParty()).thenReturn(List.of(
                Map.of("CustCode001","Customer code 1"),
                Map.of("CustCode002","Customer code 2"),
                Map.of("CustCode003","Customer code 3")
        ));

        Map<FilterOptions, List<FilteringOptionsListResponse>> map = accountingCorePresentationViewService.getFilterOptions(Arrays.stream(FilterOptions.values()).toList(), "org123");

        assertFalse(map.isEmpty());
        assertEquals(FilterOptions.values().length, map.size());
        assertEquals(2, map.get(FilterOptions.USERS).size());
        assertEquals(1, map.get(FilterOptions.DOCUMENT_NUMBERS).size());
        assertEquals(TransactionType.values().length, map.get(FilterOptions.TRANSACTION_TYPES).size());
        assertEquals(3, map.get(FilterOptions.COUNTER_PARTY).size());
        assertEquals(Counterparty.Type.values().length, map.get(FilterOptions.COUNTER_PARTY_TYPE).size());


        verify(transactionBatchRepositoryGateway).findBatchUsersList("org123");
        verifyNoMoreInteractions(transactionBatchRepositoryGateway);
        verify(transactionItemRepository).getAllDocumentNumbers();
        verify(transactionItemRepository).getAllCounterParty();
        verifyNoMoreInteractions(transactionItemRepository);
        verifyNoMoreInteractions(organisationPublicApiIF);

    }

}
