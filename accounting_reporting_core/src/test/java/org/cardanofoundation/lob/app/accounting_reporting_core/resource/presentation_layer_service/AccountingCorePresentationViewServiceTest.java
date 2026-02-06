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

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
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


        when(accountingCoreTransactionRepository.findAllByStatus("org123", List.of(), List.of(), Pageable.unpaged())).thenReturn(List.of());
        accountingCorePresentationViewService.downloadCsvTransactions("org123", List.of(), List.of(), outputStream);

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

        when(accountingCoreTransactionRepository.findAllByStatus("org123", List.of(), List.of(), Pageable.unpaged())).thenReturn(List.of(transactionEntity));

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        accountingCorePresentationViewService.downloadCsvTransactions("org123", List.of(), List.of(), outputStream);

        String csv = outputStream.toString(StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");

        assertEquals(2, lines.length); // Only header line should be present
        assertEquals("Transaction Number,Transaction Date,Transaction Type,Fx Rate,AmountLCY Debit,AmountLCY Credit,AmountFCY Debit,AmountFCY Credit,Debit Code,Debit Name,Credit Code,Credit Name,Project Code,Document Name,Currency,VAT Rate,VAT Code,Cost Center Code,Counterparty Code,Counterparty Name,Extractor Type,Ledger Dispatch Status,Blockchain Hash", lines[0]);
        assertEquals("TXN123,2026-01-01,1,,100,,100,,,,,,,,0,,,,ERP,FINALIZED,", lines[1]);
    }

}
