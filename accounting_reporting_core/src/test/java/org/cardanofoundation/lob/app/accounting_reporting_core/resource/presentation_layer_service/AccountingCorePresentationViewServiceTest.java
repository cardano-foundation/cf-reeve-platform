package org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;

import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FilterOptions;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionWithViolationDto;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconciliationStatisticProjection;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationRejectionCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationViolation;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.ReconcilationRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepositoryGateway;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionReconcilationRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterSource;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterStatusRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationRejectionCodeRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationStatisticRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.response.FilteringOptionsListResponse;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReconciliationResponseView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReconciliationStatisticView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.TransactionReconciliationTransactionsView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.AccountingCoreService;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.TransactionRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
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
    void getFilterOptions_reconciliationSources_includesAllSourcesIncludingCSV() {
        Map<FilterOptions, List<FilteringOptionsListResponse>> map = accountingCorePresentationViewService.getFilterOptions(
                List.of(FilterOptions.RECONCILIATION_SOURCES), "org123");

        assertFalse(map.isEmpty());
        List<FilteringOptionsListResponse> sources = map.get(FilterOptions.RECONCILIATION_SOURCES);
        assertEquals(ReconciliationFilterSource.values().length, sources.size());
        List<String> sourceNames = sources.stream().map(FilteringOptionsListResponse::getName).toList();
        assertTrue(sourceNames.contains("CSV"));
        assertTrue(sourceNames.contains("ERP"));
        assertTrue(sourceNames.contains("BLOCKCHAIN"));
        verifyNoInteractions(transactionBatchRepositoryGateway, transactionItemRepository);
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

    @Test
    void downloadCsvTransaction_emptyData() {


        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();


        when(accountingCoreTransactionRepository.findAllByStatusAndTypeAndInDateRange("org123", List.of(), List.of(), LocalDate.EPOCH, LocalDate.MAX, null,Pageable.unpaged())).thenReturn(List.of());
        accountingCorePresentationViewService.downloadCsvTransactions("org123", List.of(), List.of(), LocalDate.EPOCH, LocalDate.MAX, null,outputStream);

        String csv = outputStream.toString(StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");

        assertEquals(1, lines.length); // Only header line should be present
        assertEquals("Transaction Number,Transaction Date,Transaction Type,Fx Rate,AmountLCY Debit,AmountLCY Credit,AmountFCY Debit,AmountFCY Credit,Debit Code,Debit Name,Credit Code,Credit Name,Project Code,Document Name,Currency,VAT Rate,VAT Code,Cost Center Code,Counterparty Code,Counterparty Name,Extractor Type,Ledger Dispatch Status,Blockchain Hash", lines[0]);
    }

    @Test
    void downloadCsvTransaction_success() {

        TransactionEntity transactionEntity = mock(TransactionEntity.class);
        TransactionItemEntity itemEntity = mock(TransactionItemEntity.class);
        when(transactionEntity.getInternalTransactionNumber()).thenReturn("TXN123");
        when(transactionEntity.getEntryDate()).thenReturn(LocalDate.of(2026, 1,1));
        when(transactionEntity.getExtractorType()).thenReturn("ERP");
        when(transactionEntity.getLedgerDispatchStatus()).thenReturn(LedgerDispatchStatus.FINALIZED);
        when(transactionEntity.getLedgerDispatchReceipt()).thenReturn(Optional.empty());
        when(transactionEntity.getItems()).thenReturn(Set.of(itemEntity));
        when(itemEntity.getFxRate()).thenReturn(BigDecimal.ONE);
        when(itemEntity.getOperationType()).thenReturn(OperationType.CREDIT);
        when(itemEntity.getAmountLcy()).thenReturn(BigDecimal.valueOf(100));
        when(itemEntity.getAmountFcy()).thenReturn(BigDecimal.valueOf(100));
        when(itemEntity.getAccountDebit()).thenReturn(Optional.empty());
        when(itemEntity.getAccountCredit()).thenReturn(Optional.empty());
        when(itemEntity.getProject()).thenReturn(Optional.empty());
        when(itemEntity.getDocument()).thenReturn(Optional.empty());

        when(accountingCoreTransactionRepository.findAllByStatusAndTypeAndInDateRange("org123", List.of(), List.of(), LocalDate.EPOCH, LocalDate.MAX, null,Pageable.unpaged())).thenReturn(List.of(transactionEntity));

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        accountingCorePresentationViewService.downloadCsvTransactions("org123", List.of(), List.of(), LocalDate.EPOCH, LocalDate.MAX, null,outputStream);

        String csv = outputStream.toString(StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");

        assertEquals(2, lines.length); // Only header line should be present
        assertEquals("Transaction Number,Transaction Date,Transaction Type,Fx Rate,AmountLCY Debit,AmountLCY Credit,AmountFCY Debit,AmountFCY Credit,Debit Code,Debit Name,Credit Code,Credit Name,Project Code,Document Name,Currency,VAT Rate,VAT Code,Cost Center Code,Counterparty Code,Counterparty Name,Extractor Type,Ledger Dispatch Status,Blockchain Hash", lines[0]);
        assertEquals("TXN123,2026-01-01,,1,,100,,100,,,,,,,,0,,,,,ERP,FINALIZED,", lines[1]);
    }

    // --- getReconciliationStatisticByDateRange tests ---

    private ReconciliationStatisticProjection createProjection(int year, int month, long reconciled, long unreconciled) {
        ReconciliationStatisticProjection projection = mock(ReconciliationStatisticProjection.class);
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

    @Test
    void expandSorts_nullPageable_returnsNull() {
        Pageable result = accountingCorePresentationViewService.expandSorts(null, true);
        assertNull(result);
    }

    @Test
    void expandSorts_unsortedPageable_returnsUnchanged() {
        Pageable pageable = Pageable.unpaged();
        Pageable result = accountingCorePresentationViewService.expandSorts(pageable, true);
        assertEquals(pageable, result);
    }

    @Test
    void expandSorts_simpleField_mapRvFalse_onlyTrPrefixed() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "entryDate"));
        Pageable result = accountingCorePresentationViewService.expandSorts(pageable, false);

        List<Sort.Order> orders = result.getSort().stream().toList();
        assertEquals(1, orders.size());
        assertEquals("tr.entryDate", orders.get(0).getProperty());
        assertEquals(Sort.Direction.ASC, orders.get(0).getDirection());
    }

    @Test
    void expandSorts_fieldNotInRvMap_mapRvTrue_onlyTrPrefixed() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "someOtherField"));
        Pageable result = accountingCorePresentationViewService.expandSorts(pageable, true);

        List<Sort.Order> orders = result.getSort().stream().toList();
        assertEquals(1, orders.size());
        assertEquals("tr.someOtherField", orders.get(0).getProperty());
        assertEquals(Sort.Direction.DESC, orders.get(0).getDirection());
    }

    @Test
    void expandSorts_fieldWithRvMapping_mapRvTrue_addsRvAndTrOrders() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "entryDate"));
        Pageable result = accountingCorePresentationViewService.expandSorts(pageable, true);

        List<Sort.Order> orders = result.getSort().stream().toList();
        assertEquals(2, orders.size());
        assertEquals("rv.transactionEntryDate", orders.get(0).getProperty());
        assertEquals(Sort.Direction.DESC, orders.get(0).getDirection());
        assertEquals("tr.entryDate", orders.get(1).getProperty());
        assertEquals(Sort.Direction.DESC, orders.get(1).getDirection());
    }

    @Test
    void expandSorts_fieldWithRvMapping_mapRvFalse_onlyTrPrefixed() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "totalAmountLcy"));
        Pageable result = accountingCorePresentationViewService.expandSorts(pageable, false);

        List<Sort.Order> orders = result.getSort().stream().toList();
        assertEquals(1, orders.size());
        assertEquals("tr.totalAmountLcy", orders.get(0).getProperty());
    }

    @Test
    void expandSorts_internalTransactionNumberMapping_mapRvTrue() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "internalTransactionNumber"));
        Pageable result = accountingCorePresentationViewService.expandSorts(pageable, true);

        List<Sort.Order> orders = result.getSort().stream().toList();
        assertEquals(2, orders.size());
        assertEquals("rv.transactionInternalNumber", orders.get(0).getProperty());
        assertEquals("tr.internalTransactionNumber", orders.get(1).getProperty());
    }

    @Test
    void expandSorts_functionSort_mapRvTrue_addsRvAndTrFunctionOrders() {
        Sort jpaSort = JpaSort.unsafe(Sort.Direction.ASC, "function('enum_to_text', transactionType)");
        Pageable pageable = PageRequest.of(0, 10, jpaSort);
        Pageable result = accountingCorePresentationViewService.expandSorts(pageable, true);

        List<Sort.Order> orders = result.getSort().stream().toList();
        assertEquals(2, orders.size());
        assertEquals("function('enum_to_text', rv.transactionType)", orders.get(0).getProperty());
        assertEquals(Sort.Direction.ASC, orders.get(0).getDirection());
        assertEquals("function('enum_to_text', tr.transactionType)", orders.get(1).getProperty());
        assertEquals(Sort.Direction.ASC, orders.get(1).getDirection());
    }

    @Test
    void expandSorts_functionSort_mapRvFalse_onlyTrFunctionOrder() {
        Sort jpaSort = JpaSort.unsafe(Sort.Direction.DESC, "function('enum_to_text', transactionType)");
        Pageable pageable = PageRequest.of(0, 10, jpaSort);
        Pageable result = accountingCorePresentationViewService.expandSorts(pageable, false);

        List<Sort.Order> orders = result.getSort().stream().toList();
        assertEquals(1, orders.size());
        assertEquals("function('enum_to_text', tr.transactionType)", orders.get(0).getProperty());
        assertEquals(Sort.Direction.DESC, orders.get(0).getDirection());
    }

    @Test
    void expandSorts_multipleFields_mapRvTrue_expandsAll() {
        Sort sort = Sort.by(Sort.Direction.ASC, "entryDate").and(Sort.by(Sort.Direction.DESC, "totalAmountLcy"));
        Pageable pageable = PageRequest.of(0, 10, sort);
        Pageable result = accountingCorePresentationViewService.expandSorts(pageable, true);

        List<Sort.Order> orders = result.getSort().stream().toList();
        // entryDate → rv.transactionEntryDate + tr.entryDate
        // totalAmountLcy → rv.amountLcySum + tr.totalAmountLcy
        assertEquals(4, orders.size());
        assertEquals("rv.transactionEntryDate", orders.get(0).getProperty());
        assertEquals(Sort.Direction.ASC, orders.get(0).getDirection());
        assertEquals("tr.entryDate", orders.get(1).getProperty());
        assertEquals(Sort.Direction.ASC, orders.get(1).getDirection());
        assertEquals("rv.amountLcySum", orders.get(2).getProperty());
        assertEquals(Sort.Direction.DESC, orders.get(2).getDirection());
        assertEquals("tr.totalAmountLcy", orders.get(3).getProperty());
        assertEquals(Sort.Direction.DESC, orders.get(3).getDirection());
    }

    @Test
    void testAllReconciliationTransaction_reconciledFilter_withTransactionId() {
        when(reconcilationRepository.findCalcReconciliationStatistic()).thenReturn(new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
        when(transactionReconcilationRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        when(reconcilationRepository.findAllReconcilation(eq("RECONCILED"), eq(null), eq(null), eq(null), eq("TXN-123"), eq(null), any(Pageable.class))).thenReturn(Page.empty());

        ReconciliationFilterRequest body = mock(ReconciliationFilterRequest.class);
        when(body.getFilter()).thenReturn(ReconciliationFilterStatusRequest.RECONCILED);
        when(body.getTransactionId()).thenReturn("TXN-123");

        accountingCorePresentationViewService.allReconciliationTransaction(body, Pageable.unpaged());

        verify(reconcilationRepository).findAllReconcilation(eq("RECONCILED"), eq(null), eq(null), eq(null), eq("TXN-123"), eq(null), any(Pageable.class));
        verifyNoInteractions(accountingCoreService, transactionBatchRepositoryGateway, transactionRepositoryGateway);
    }

    @Test
    void testAllReconciliationTransaction_unreconciledFilter_withTransactionId() {
        when(reconcilationRepository.findCalcReconciliationStatistic()).thenReturn(new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
        when(transactionReconcilationRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        when(reconcilationRepository.findAllReconciliationSpecial(eq(null), eq(null), eq(null), eq(null), eq(null), eq("TXN-123"), any(Pageable.class))).thenReturn(Page.empty());

        ReconciliationFilterRequest body = mock(ReconciliationFilterRequest.class);
        when(body.getFilter()).thenReturn(ReconciliationFilterStatusRequest.UNRECONCILED);
        when(body.getTransactionId()).thenReturn("TXN-123");

        accountingCorePresentationViewService.allReconciliationTransaction(body, Pageable.unpaged());

        verify(reconcilationRepository).findAllReconciliationSpecial(eq(null), eq(null), eq(null), eq(null), eq(null), eq("TXN-123"), any(Pageable.class));
        verifyNoInteractions(accountingCoreService, transactionBatchRepositoryGateway, transactionRepositoryGateway);
    }

    @Test
    void allReconciliationTransaction_reconciledFilter_withDatesAndSource() {
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 12, 31);

        when(reconcilationRepository.findCalcReconciliationStatistic()).thenReturn(new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
        when(transactionReconcilationRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        when(reconcilationRepository.findAllReconcilation(
                eq("RECONCILED"), eq(dateFrom), eq(dateTo), eq(null), eq(null), eq("ERP"), any(Pageable.class))
        ).thenReturn(Page.empty());

        ReconciliationFilterRequest body = mock(ReconciliationFilterRequest.class);
        when(body.getFilter()).thenReturn(ReconciliationFilterStatusRequest.RECONCILED);
        when(body.getDateFrom()).thenReturn(Optional.of(dateFrom));
        when(body.getDateTo()).thenReturn(Optional.of(dateTo));
        when(body.getSource()).thenReturn(Optional.of(ReconciliationFilterSource.ERP));

        accountingCorePresentationViewService.allReconciliationTransaction(body, Pageable.unpaged());

        verify(reconcilationRepository).findAllReconcilation(
                eq("RECONCILED"), eq(dateFrom), eq(dateTo), eq(null), eq(null), eq("ERP"), any(Pageable.class));
        verifyNoInteractions(accountingCoreService, transactionBatchRepositoryGateway, transactionRepositoryGateway);
    }

    @Test
    void allReconciliationTransaction_reconciledFilter_withCSVSource() {
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 12, 31);

        when(reconcilationRepository.findCalcReconciliationStatistic()).thenReturn(new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
        when(transactionReconcilationRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        when(reconcilationRepository.findAllReconcilation(
                eq("RECONCILED"), eq(dateFrom), eq(dateTo), eq(null), eq(null), eq("CSV"), any(Pageable.class))
        ).thenReturn(Page.empty());

        ReconciliationFilterRequest body = mock(ReconciliationFilterRequest.class);
        when(body.getFilter()).thenReturn(ReconciliationFilterStatusRequest.RECONCILED);
        when(body.getDateFrom()).thenReturn(Optional.of(dateFrom));
        when(body.getDateTo()).thenReturn(Optional.of(dateTo));
        when(body.getSource()).thenReturn(Optional.of(ReconciliationFilterSource.CSV));
        when(body.getTransactionTypes()).thenReturn(Set.of());
        when(body.getReconciliationRejectionCode()).thenReturn(Set.of());

        accountingCorePresentationViewService.allReconciliationTransaction(body, Pageable.unpaged());

        verify(reconcilationRepository).findAllReconcilation(
                eq("RECONCILED"), eq(dateFrom), eq(dateTo), eq(null), eq(null), eq("CSV"), any(Pageable.class));
        verifyNoInteractions(accountingCoreService, transactionBatchRepositoryGateway, transactionRepositoryGateway);
    }

    @Test
    void allReconciliationTransaction_unreconciledFilter_withCSVSource() {
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 12, 31);

        when(reconcilationRepository.findCalcReconciliationStatistic()).thenReturn(new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
        when(transactionReconcilationRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        when(reconcilationRepository.findAllReconciliationSpecial(
                eq(null), eq(dateFrom), eq(dateTo), eq("CSV"), eq(null), eq(null), any(Pageable.class))
        ).thenReturn(Page.empty());

        ReconciliationFilterRequest body = mock(ReconciliationFilterRequest.class);
        when(body.getFilter()).thenReturn(ReconciliationFilterStatusRequest.UNRECONCILED);
        when(body.getDateFrom()).thenReturn(Optional.of(dateFrom));
        when(body.getDateTo()).thenReturn(Optional.of(dateTo));
        when(body.getSource()).thenReturn(Optional.of(ReconciliationFilterSource.CSV));
        when(body.getTransactionTypes()).thenReturn(Set.of());
        when(body.getReconciliationRejectionCode()).thenReturn(Set.of());
        when(body.getTransactionId()).thenReturn(null);

        accountingCorePresentationViewService.allReconciliationTransaction(body, Pageable.unpaged());

        verify(reconcilationRepository).findAllReconciliationSpecial(
                eq(null), eq(dateFrom), eq(dateTo), eq("CSV"), eq(null), eq(null), any(Pageable.class));
        verifyNoInteractions(accountingCoreService, transactionBatchRepositoryGateway, transactionRepositoryGateway);
    }

    @Test
    void allReconciliationTransaction_unreconciledFilter_withNonEmptyRejectionCodesAndTypes() {
        when(reconcilationRepository.findCalcReconciliationStatistic()).thenReturn(new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
        when(transactionReconcilationRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        when(reconcilationRepository.findAllReconciliationSpecial(any(), any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        ReconciliationFilterRequest body = mock(ReconciliationFilterRequest.class);
        when(body.getFilter()).thenReturn(ReconciliationFilterStatusRequest.UNRECONCILED);
        when(body.getReconciliationRejectionCode()).thenReturn(Set.of(ReconciliationRejectionCodeRequest.MISSING_IN_ERP));
        when(body.getTransactionTypes()).thenReturn(Set.of(TransactionType.Journal));

        accountingCorePresentationViewService.allReconciliationTransaction(body, Pageable.unpaged());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<ReconcilationRejectionCode>> rejectionCodesCaptor = ArgumentCaptor.forClass(Set.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<TransactionType>> typesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(reconcilationRepository).findAllReconciliationSpecial(
                rejectionCodesCaptor.capture(), any(), any(), any(), typesCaptor.capture(), any(), any(Pageable.class));

        assertEquals(Set.of(ReconcilationRejectionCode.TX_NOT_IN_ERP), rejectionCodesCaptor.getValue());
        assertEquals(Set.of(TransactionType.Journal), typesCaptor.getValue());
        verifyNoInteractions(accountingCoreService, transactionBatchRepositoryGateway, transactionRepositoryGateway);
    }

    @Test
    void allReconciliationTransaction_reconciledFilter_withSortedPageable_expandsSort() {
        when(reconcilationRepository.findCalcReconciliationStatistic()).thenReturn(new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
        when(transactionReconcilationRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        when(reconcilationRepository.findAllReconcilation(any(), any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        ReconciliationFilterRequest body = mock(ReconciliationFilterRequest.class);
        when(body.getFilter()).thenReturn(ReconciliationFilterStatusRequest.RECONCILED);

        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "entryDate"));
        accountingCorePresentationViewService.allReconciliationTransaction(body, pageable);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(reconcilationRepository).findAllReconcilation(any(), any(), any(), any(), any(), any(), pageableCaptor.capture());

        List<Sort.Order> orders = pageableCaptor.getValue().getSort().stream().toList();
        assertEquals(1, orders.size());
        assertEquals("tr.entryDate", orders.get(0).getProperty());
        assertEquals(Sort.Direction.ASC, orders.get(0).getDirection());
    }

    @Test
    void allReconciliationTransaction_unreconciledFilter_withSortedPageable_expandsSortWithRvMapping() {
        when(reconcilationRepository.findCalcReconciliationStatistic()).thenReturn(new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
        when(transactionReconcilationRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        when(reconcilationRepository.findAllReconciliationSpecial(any(), any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        ReconciliationFilterRequest body = mock(ReconciliationFilterRequest.class);
        when(body.getFilter()).thenReturn(ReconciliationFilterStatusRequest.UNRECONCILED);

        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "entryDate"));
        accountingCorePresentationViewService.allReconciliationTransaction(body, pageable);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(reconcilationRepository).findAllReconciliationSpecial(any(), any(), any(), any(), any(), any(), pageableCaptor.capture());

        List<Sort.Order> orders = pageableCaptor.getValue().getSort().stream().toList();
        assertEquals(2, orders.size());
        assertEquals("rv.transactionEntryDate", orders.get(0).getProperty());
        assertEquals(Sort.Direction.DESC, orders.get(0).getDirection());
        assertEquals("tr.entryDate", orders.get(1).getProperty());
        assertEquals(Sort.Direction.DESC, orders.get(1).getDirection());
    }

    @Test
    void allReconciliationTransaction_reconciledFilter_resultsPreserveInsertionOrder() {
        when(reconcilationRepository.findCalcReconciliationStatistic()).thenReturn(new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
        when(transactionReconcilationRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        when(reconcilationRepository.findAllReconcilation(any(), any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        ReconciliationFilterRequest body = mock(ReconciliationFilterRequest.class);
        when(body.getFilter()).thenReturn(ReconciliationFilterStatusRequest.RECONCILED);

        ReconciliationResponseView result = accountingCorePresentationViewService.allReconciliationTransaction(body, Pageable.unpaged());

        assertTrue(result.getTransactions() instanceof LinkedHashSet,
                "Transactions should be a LinkedHashSet to preserve ordering");
    }

    // --- getReconciliationTransactionsSelector tests (via allReconciliationTransaction UNRECONCILED path) ---

    @Test
    void getReconciliationTransactionsSelector_violationOnly_propagatesLastReconciledDate() {
        LocalDateTime lastReconciledDate = LocalDateTime.of(2024, 6, 15, 10, 30, 0);

        ReconcilationViolation violation = ReconcilationViolation.builder()
                .transactionId("TX-001")
                .transactionInternalNumber("INT-001")
                .transactionEntryDate(LocalDate.of(2024, 1, 1))
                .transactionType(TransactionType.Journal)
                .rejectionCode(ReconcilationRejectionCode.TX_NOT_IN_ERP)
                .amountLcySum(BigDecimal.valueOf(100))
                .build();

        TransactionWithViolationDto dto = new TransactionWithViolationDto(null, violation, lastReconciledDate);

        when(reconcilationRepository.findCalcReconciliationStatistic())
                .thenReturn(new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
        when(transactionReconcilationRepository.findTopByOrderByCreatedAtDesc())
                .thenReturn(Optional.empty());
        when(reconcilationRepository.findAllReconciliationSpecial(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dto)));

        ReconciliationFilterRequest body = mock(ReconciliationFilterRequest.class);
        when(body.getFilter()).thenReturn(ReconciliationFilterStatusRequest.UNRECONCILED);

        ReconciliationResponseView result = accountingCorePresentationViewService.allReconciliationTransaction(body, Pageable.unpaged());

        assertEquals(1, result.getTransactions().size());
        TransactionReconciliationTransactionsView view = result.getTransactions().iterator().next();
        assertEquals(lastReconciledDate, view.getReconciliationDate());
        assertEquals("TX-001", view.getId());
        assertEquals(TransactionReconciliationTransactionsView.ReconciliationCodeView.NOK, view.getReconciliationFinalStatus());
    }

    @Test
    void getReconciliationTransactionsSelector_violationOnly_nullLastReconciledDate_reconciliationDateIsNull() {
        ReconcilationViolation violation = ReconcilationViolation.builder()
                .transactionId("TX-002")
                .transactionInternalNumber("INT-002")
                .transactionEntryDate(LocalDate.of(2024, 3, 1))
                .transactionType(TransactionType.CardCharge)
                .rejectionCode(ReconcilationRejectionCode.TX_NOT_IN_LOB)
                .amountLcySum(BigDecimal.valueOf(200))
                .build();

        TransactionWithViolationDto dto = new TransactionWithViolationDto(null, violation, null);

        when(reconcilationRepository.findCalcReconciliationStatistic())
                .thenReturn(new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
        when(transactionReconcilationRepository.findTopByOrderByCreatedAtDesc())
                .thenReturn(Optional.empty());
        when(reconcilationRepository.findAllReconciliationSpecial(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dto)));

        ReconciliationFilterRequest body = mock(ReconciliationFilterRequest.class);
        when(body.getFilter()).thenReturn(ReconciliationFilterStatusRequest.UNRECONCILED);

        ReconciliationResponseView result = accountingCorePresentationViewService.allReconciliationTransaction(body, Pageable.unpaged());

        assertEquals(1, result.getTransactions().size());
        TransactionReconciliationTransactionsView view = result.getTransactions().iterator().next();
        assertNull(view.getReconciliationDate());
        assertEquals("TX-002", view.getId());
    }

    @Test
    void getReconciliationTransactionsSelector_txPresent_usesTxPathAndIgnoresLastReconciledDate() {
        TransactionEntity txEntity = mock(TransactionEntity.class);
        when(txEntity.getId()).thenReturn("TX-003");
        when(txEntity.getExtractorType()).thenReturn("NETSUITE");
        when(txEntity.getOverallStatus()).thenReturn(TransactionStatus.OK);
        when(txEntity.getAutomatedValidationStatus()).thenReturn(TxValidationStatus.VALIDATED);
        when(txEntity.getReconcilation()).thenReturn(Optional.empty());
        when(txEntity.getLastReconcilation()).thenReturn(Optional.empty());

        TransactionWithViolationDto dto = new TransactionWithViolationDto(
                txEntity, null, LocalDateTime.of(2024, 6, 15, 10, 30, 0));

        when(reconcilationRepository.findCalcReconciliationStatistic())
                .thenReturn(new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
        when(transactionReconcilationRepository.findTopByOrderByCreatedAtDesc())
                .thenReturn(Optional.empty());
        when(reconcilationRepository.findAllReconciliationSpecial(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dto)));

        ReconciliationFilterRequest body = mock(ReconciliationFilterRequest.class);
        when(body.getFilter()).thenReturn(ReconciliationFilterStatusRequest.UNRECONCILED);

        ReconciliationResponseView result = accountingCorePresentationViewService.allReconciliationTransaction(body, Pageable.unpaged());

        assertEquals(1, result.getTransactions().size());
        TransactionReconciliationTransactionsView view = result.getTransactions().iterator().next();
        assertEquals("TX-003", view.getId());
        // lastReconciledDate from the DTO is not used in this path — the tx's own reconciliation data is used
        assertNull(view.getReconciliationDate());
    }

    @Test
    void getReconciliationTransactionsSelector_bothTxAndViolationNull_returnsFallbackView() {
        TransactionWithViolationDto dto = new TransactionWithViolationDto(
                null, null, LocalDateTime.of(2024, 6, 15, 10, 30, 0));

        when(reconcilationRepository.findCalcReconciliationStatistic())
                .thenReturn(new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
        when(transactionReconcilationRepository.findTopByOrderByCreatedAtDesc())
                .thenReturn(Optional.empty());
        when(reconcilationRepository.findAllReconciliationSpecial(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dto)));

        ReconciliationFilterRequest body = mock(ReconciliationFilterRequest.class);
        when(body.getFilter()).thenReturn(ReconciliationFilterStatusRequest.UNRECONCILED);

        ReconciliationResponseView result = accountingCorePresentationViewService.allReconciliationTransaction(body, Pageable.unpaged());

        assertEquals(1, result.getTransactions().size());
        TransactionReconciliationTransactionsView view = result.getTransactions().iterator().next();
        // fallback view returns empty string id and null reconciliation date
        assertEquals("", view.getId());
        assertNull(view.getReconciliationDate());
        assertEquals(TransactionReconciliationTransactionsView.ReconciliationCodeView.NOK, view.getReconciliationFinalStatus());
    }
}
