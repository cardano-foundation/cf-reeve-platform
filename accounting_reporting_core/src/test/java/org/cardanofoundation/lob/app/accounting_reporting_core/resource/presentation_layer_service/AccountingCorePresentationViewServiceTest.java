package org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
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
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.ReconcilationRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepositoryGateway;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionReconcilationRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterStatusRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationStatisticRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.response.FilteringOptionsListResponse;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReconciliationResponseView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReconciliationStatisticView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.AccountingCoreService;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.TransactionRepositoryGateway;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;

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
        Assertions.assertEquals(18, responseView.getStatistic().getTOTAL()); // Array index 5 + Array index 6
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


        when(transactionItemRepository.getAllCounterParty("org123")).thenReturn(List.of(
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
        verify(transactionItemRepository, times(2)).getAllCounterParty("org123");
        verifyNoMoreInteractions(transactionItemRepository);
        verifyNoMoreInteractions(organisationPublicApiIF);

    }

    // --- getReconciliationStatisticByDateRange tests ---

    private ReconcilationRepository.ReconciliationStatisticProjection createProjection(int year, int month, long reconciled, long unreconciled) {
        ReconcilationRepository.ReconciliationStatisticProjection projection = mock(ReconcilationRepository.ReconciliationStatisticProjection.class);
        when(projection.getYear()).thenReturn(year);
        when(projection.getMonth()).thenReturn(month);
        when(projection.getReconciledCount()).thenReturn(reconciled);
        when(projection.getUnreconciledCount()).thenReturn(unreconciled);
        return projection;
    }

    private ReconciliationStatisticRequest createStatisticRequest(String orgId, LocalDate dateFrom, LocalDate dateTo, IntervalType aggregate) {
        ReconciliationStatisticRequest request = new ReconciliationStatisticRequest(dateFrom, dateTo, aggregate);
        request.setOrganisationId(orgId);
        return request;
    }

    @Test
    void getReconciliationStatisticByDateRange_aggregateTotal_noRows() {
        ReconciliationStatisticRequest request = createStatisticRequest("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), null);

        when(reconcilationRepository.findReconciliationStatisticByDateRange("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)))
                .thenReturn(List.of());

        Map<String, ReconciliationStatisticView> result = accountingCorePresentationViewService.getReconciliationStatisticByDateRange(request);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("STATISTICS"));
        assertEquals(0L, result.get("STATISTICS").getReconciledCount());
        assertEquals(0L, result.get("STATISTICS").getUnreconciledCount());
        verify(reconcilationRepository).findReconciliationStatisticByDateRange("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
    }

    @Test
    void getReconciliationStatisticByDateRange_aggregateTotal_withRows() {
        ReconciliationStatisticRequest request = createStatisticRequest("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), null);
        var p1 = createProjection(2024, 1, 10L, 5L);
        var p2 = createProjection(2024, 2, 20L, 3L);
        var p3 = createProjection(2024, 3, 15L, 7L);

        when(reconcilationRepository.findReconciliationStatisticByDateRange("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)))
                .thenReturn(List.of(p1, p2, p3));

        Map<String, ReconciliationStatisticView> result = accountingCorePresentationViewService.getReconciliationStatisticByDateRange(request);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("STATISTICS"));
        assertEquals(45L, result.get("STATISTICS").getReconciledCount());
        assertEquals(15L, result.get("STATISTICS").getUnreconciledCount());
    }

    @Test
    void getReconciliationStatisticByDateRange_aggregateByMonth_singleYear() {
        ReconciliationStatisticRequest request = createStatisticRequest("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 31), IntervalType.MONTH);
        var p1 = createProjection(2024, 1, 10L, 5L);
        var p2 = createProjection(2024, 2, 20L, 3L);
        var p3 = createProjection(2024, 3, 15L, 7L);

        when(reconcilationRepository.findReconciliationStatisticByDateRange("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 31)))
                .thenReturn(List.of(p1, p2, p3));

        Map<String, ReconciliationStatisticView> result = accountingCorePresentationViewService.getReconciliationStatisticByDateRange(request);

        assertEquals(3, result.size());
        assertTrue(result.containsKey("JANUARY"));
        assertTrue(result.containsKey("FEBRUARY"));
        assertTrue(result.containsKey("MARCH"));
        assertEquals(10L, result.get("JANUARY").getReconciledCount());
        assertEquals(5L, result.get("JANUARY").getUnreconciledCount());
        assertEquals(20L, result.get("FEBRUARY").getReconciledCount());
        assertEquals(3L, result.get("FEBRUARY").getUnreconciledCount());
        assertEquals(15L, result.get("MARCH").getReconciledCount());
        assertEquals(7L, result.get("MARCH").getUnreconciledCount());
    }

    @Test
    void getReconciliationStatisticByDateRange_aggregateByMonth_multiYear() {
        ReconciliationStatisticRequest request = createStatisticRequest("org1", LocalDate.of(2023, 11, 1), LocalDate.of(2024, 2, 28), IntervalType.MONTH);
        var p1 = createProjection(2023, 11, 8L, 2L);
        var p2 = createProjection(2023, 12, 12L, 4L);
        var p3 = createProjection(2024, 1, 10L, 5L);
        var p4 = createProjection(2024, 2, 20L, 3L);

        when(reconcilationRepository.findReconciliationStatisticByDateRange("org1", LocalDate.of(2023, 11, 1), LocalDate.of(2024, 2, 28)))
                .thenReturn(List.of(p1, p2, p3, p4));

        Map<String, ReconciliationStatisticView> result = accountingCorePresentationViewService.getReconciliationStatisticByDateRange(request);

        assertEquals(4, result.size());
        assertTrue(result.containsKey("NOVEMBER_2023"));
        assertTrue(result.containsKey("DECEMBER_2023"));
        assertTrue(result.containsKey("JANUARY_2024"));
        assertTrue(result.containsKey("FEBRUARY_2024"));
        assertEquals(8L, result.get("NOVEMBER_2023").getReconciledCount());
        assertEquals(2L, result.get("NOVEMBER_2023").getUnreconciledCount());
        assertEquals(12L, result.get("DECEMBER_2023").getReconciledCount());
        assertEquals(4L, result.get("DECEMBER_2023").getUnreconciledCount());
        assertEquals(10L, result.get("JANUARY_2024").getReconciledCount());
        assertEquals(5L, result.get("JANUARY_2024").getUnreconciledCount());
        assertEquals(20L, result.get("FEBRUARY_2024").getReconciledCount());
        assertEquals(3L, result.get("FEBRUARY_2024").getUnreconciledCount());
    }

    @Test
    void getReconciliationStatisticByDateRange_aggregateByQuarter_singleYear() {
        ReconciliationStatisticRequest request = createStatisticRequest("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 30), IntervalType.QUARTER);
        var p1 = createProjection(2024, 1, 10L, 5L);
        var p2 = createProjection(2024, 2, 20L, 3L);
        var p3 = createProjection(2024, 3, 15L, 7L);
        var p4 = createProjection(2024, 4, 8L, 2L);
        var p5 = createProjection(2024, 5, 12L, 4L);
        var p6 = createProjection(2024, 6, 6L, 1L);

        when(reconcilationRepository.findReconciliationStatisticByDateRange("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 30)))
                .thenReturn(List.of(p1, p2, p3, p4, p5, p6));

        Map<String, ReconciliationStatisticView> result = accountingCorePresentationViewService.getReconciliationStatisticByDateRange(request);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("Q1"));
        assertTrue(result.containsKey("Q2"));
        assertEquals(45L, result.get("Q1").getReconciledCount());
        assertEquals(15L, result.get("Q1").getUnreconciledCount());
        assertEquals(26L, result.get("Q2").getReconciledCount());
        assertEquals(7L, result.get("Q2").getUnreconciledCount());
    }

    @Test
    void getReconciliationStatisticByDateRange_aggregateByQuarter_multiYear() {
        ReconciliationStatisticRequest request = createStatisticRequest("org1", LocalDate.of(2023, 10, 1), LocalDate.of(2024, 3, 31), IntervalType.QUARTER);
        var p1 = createProjection(2023, 10, 5L, 1L);
        var p2 = createProjection(2023, 11, 8L, 2L);
        var p3 = createProjection(2023, 12, 12L, 4L);
        var p4 = createProjection(2024, 1, 10L, 5L);
        var p5 = createProjection(2024, 2, 20L, 3L);
        var p6 = createProjection(2024, 3, 15L, 7L);

        when(reconcilationRepository.findReconciliationStatisticByDateRange("org1", LocalDate.of(2023, 10, 1), LocalDate.of(2024, 3, 31)))
                .thenReturn(List.of(p1, p2, p3, p4, p5, p6));

        Map<String, ReconciliationStatisticView> result = accountingCorePresentationViewService.getReconciliationStatisticByDateRange(request);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("Q4_2023"));
        assertTrue(result.containsKey("Q1_2024"));
        assertEquals(25L, result.get("Q4_2023").getReconciledCount());
        assertEquals(7L, result.get("Q4_2023").getUnreconciledCount());
        assertEquals(45L, result.get("Q1_2024").getReconciledCount());
        assertEquals(15L, result.get("Q1_2024").getUnreconciledCount());
    }

    @Test
    void getReconciliationStatisticByDateRange_aggregateByYear() {
        ReconciliationStatisticRequest request = createStatisticRequest("org1", LocalDate.of(2023, 1, 1), LocalDate.of(2024, 12, 31), IntervalType.YEAR);
        var p1 = createProjection(2023, 1, 10L, 5L);
        var p2 = createProjection(2023, 6, 20L, 3L);
        var p3 = createProjection(2024, 3, 15L, 7L);
        var p4 = createProjection(2024, 9, 8L, 2L);

        when(reconcilationRepository.findReconciliationStatisticByDateRange("org1", LocalDate.of(2023, 1, 1), LocalDate.of(2024, 12, 31)))
                .thenReturn(List.of(p1, p2, p3, p4));

        Map<String, ReconciliationStatisticView> result = accountingCorePresentationViewService.getReconciliationStatisticByDateRange(request);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("2023"));
        assertTrue(result.containsKey("2024"));
        assertEquals(30L, result.get("2023").getReconciledCount());
        assertEquals(8L, result.get("2023").getUnreconciledCount());
        assertEquals(23L, result.get("2024").getReconciledCount());
        assertEquals(9L, result.get("2024").getUnreconciledCount());
    }

    @Test
    void getReconciliationStatisticByDateRange_aggregateByYear_singleYear() {
        ReconciliationStatisticRequest request = createStatisticRequest("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), IntervalType.YEAR);
        var p1 = createProjection(2024, 1, 10L, 5L);
        var p2 = createProjection(2024, 6, 20L, 3L);

        when(reconcilationRepository.findReconciliationStatisticByDateRange("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)))
                .thenReturn(List.of(p1, p2));

        Map<String, ReconciliationStatisticView> result = accountingCorePresentationViewService.getReconciliationStatisticByDateRange(request);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("2024"));
        assertEquals(30L, result.get("2024").getReconciledCount());
        assertEquals(8L, result.get("2024").getUnreconciledCount());
    }

    @Test
    void getReconciliationStatisticByDateRange_aggregateByMonth_emptyRows() {
        ReconciliationStatisticRequest request = createStatisticRequest("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), IntervalType.MONTH);

        when(reconcilationRepository.findReconciliationStatisticByDateRange("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)))
                .thenReturn(List.of());

        Map<String, ReconciliationStatisticView> result = accountingCorePresentationViewService.getReconciliationStatisticByDateRange(request);

        assertTrue(result.isEmpty());
    }

    @Test
    void getReconciliationStatisticByDateRange_aggregateByQuarter_emptyRows() {
        ReconciliationStatisticRequest request = createStatisticRequest("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), IntervalType.QUARTER);

        when(reconcilationRepository.findReconciliationStatisticByDateRange("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)))
                .thenReturn(List.of());

        Map<String, ReconciliationStatisticView> result = accountingCorePresentationViewService.getReconciliationStatisticByDateRange(request);

        assertTrue(result.isEmpty());
    }

    @Test
    void getReconciliationStatisticByDateRange_aggregateByYear_emptyRows() {
        ReconciliationStatisticRequest request = createStatisticRequest("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), IntervalType.YEAR);

        when(reconcilationRepository.findReconciliationStatisticByDateRange("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)))
                .thenReturn(List.of());

        Map<String, ReconciliationStatisticView> result = accountingCorePresentationViewService.getReconciliationStatisticByDateRange(request);

        assertTrue(result.isEmpty());
    }

    @Test
    void getReconciliationStatisticByDateRange_aggregateByQuarter_allFourQuarters() {
        ReconciliationStatisticRequest request = createStatisticRequest("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), IntervalType.QUARTER);
        var p1 = createProjection(2024, 1, 1L, 1L);
        var p2 = createProjection(2024, 2, 2L, 2L);
        var p3 = createProjection(2024, 3, 3L, 3L);
        var p4 = createProjection(2024, 4, 4L, 4L);
        var p5 = createProjection(2024, 5, 5L, 5L);
        var p6 = createProjection(2024, 6, 6L, 6L);
        var p7 = createProjection(2024, 7, 7L, 7L);
        var p8 = createProjection(2024, 8, 8L, 8L);
        var p9 = createProjection(2024, 9, 9L, 9L);
        var p10 = createProjection(2024, 10, 10L, 10L);
        var p11 = createProjection(2024, 11, 11L, 11L);
        var p12 = createProjection(2024, 12, 12L, 12L);

        when(reconcilationRepository.findReconciliationStatisticByDateRange("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)))
                .thenReturn(List.of(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12));

        Map<String, ReconciliationStatisticView> result = accountingCorePresentationViewService.getReconciliationStatisticByDateRange(request);

        assertEquals(4, result.size());
        assertTrue(result.containsKey("Q1"));
        assertTrue(result.containsKey("Q2"));
        assertTrue(result.containsKey("Q3"));
        assertTrue(result.containsKey("Q4"));
        assertEquals(6L, result.get("Q1").getReconciledCount());
        assertEquals(6L, result.get("Q1").getUnreconciledCount());
        assertEquals(15L, result.get("Q2").getReconciledCount());
        assertEquals(15L, result.get("Q2").getUnreconciledCount());
        assertEquals(24L, result.get("Q3").getReconciledCount());
        assertEquals(24L, result.get("Q3").getUnreconciledCount());
        assertEquals(33L, result.get("Q4").getReconciledCount());
        assertEquals(33L, result.get("Q4").getUnreconciledCount());
    }

    @Test
    void getReconciliationStatisticByDateRange_aggregateByMonth_singleRow() {
        ReconciliationStatisticRequest request = createStatisticRequest("org1", LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30), IntervalType.MONTH);
        var p1 = createProjection(2024, 6, 42L, 8L);

        when(reconcilationRepository.findReconciliationStatisticByDateRange("org1", LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30)))
                .thenReturn(List.of(p1));

        Map<String, ReconciliationStatisticView> result = accountingCorePresentationViewService.getReconciliationStatisticByDateRange(request);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("JUNE"));
        assertEquals(42L, result.get("JUNE").getReconciledCount());
        assertEquals(8L, result.get("JUNE").getUnreconciledCount());
    }

    @Test
    void getReconciliationStatisticByDateRange_preservesKeyOrder() {
        ReconciliationStatisticRequest request = createStatisticRequest("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 31), IntervalType.MONTH);
        var p1 = createProjection(2024, 1, 10L, 5L);
        var p2 = createProjection(2024, 2, 20L, 3L);
        var p3 = createProjection(2024, 3, 15L, 7L);

        when(reconcilationRepository.findReconciliationStatisticByDateRange("org1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 31)))
                .thenReturn(List.of(p1, p2, p3));

        Map<String, ReconciliationStatisticView> result = accountingCorePresentationViewService.getReconciliationStatisticByDateRange(request);

        List<String> keys = result.keySet().stream().toList();
        assertEquals("JANUARY", keys.get(0));
        assertEquals("FEBRUARY", keys.get(1));
        assertEquals("MARCH", keys.get(2));
    }

}
