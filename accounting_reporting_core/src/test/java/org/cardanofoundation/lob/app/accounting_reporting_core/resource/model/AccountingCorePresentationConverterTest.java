package org.cardanofoundation.lob.app.accounting_reporting_core.resource.model;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.CORE_CURRENCY_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Account;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepositoryGateway;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.AccountingCorePresentationViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.BatchSearchRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ExtractionRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.LedgerDispatchStatusView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.SearchRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchsDetailView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.TransactionView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.AccountingCoreService;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.TransactionRepositoryGateway;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.repository.CostCenterRepository;
import org.cardanofoundation.lob.app.organisation.repository.ProjectMappingRepository;

@ExtendWith(MockitoExtension.class)
class AccountingCorePresentationConverterTest {

    @Mock
    private TransactionRepositoryGateway transactionRepositoryGateway;

    @Mock
    private AccountingCoreService accountingCoreService;

    @Mock
    private TransactionBatchRepositoryGateway transactionBatchRepositoryGateway;
    @Mock
    private OrganisationPublicApiIF organisationPublicApiIF;
    @Mock
    private AccountingCoreTransactionRepository transactionRepository;
    @Mock
    private CostCenterRepository costCenterRepository;
    @Mock
    private ProjectMappingRepository projectMappingRepository;

    @InjectMocks
    private AccountingCorePresentationViewService accountingCorePresentationConverter;

    @Spy
    private Clock clock = Clock.systemUTC();

    @Test
    void testAllTransactions() {

        SearchRequest searchRequest = new SearchRequest();
        TransactionItemEntity transactionItem = new TransactionItemEntity();
        TransactionItemEntity transactionItem3 = new TransactionItemEntity();
        TransactionEntity transactionEntity = new TransactionEntity();
        TransactionEntity transactionEntity2 = new TransactionEntity();
        TransactionEntity transactionEntity3 = new TransactionEntity();
        TransactionViolation transactionViolation = new TransactionViolation();
        Account accountDebit = Account.builder().name("debit").code("debit-code").refCode("dcod").build();
        Account accountCredit = new Account().toBuilder().name("credit").code("credit-code").refCode("ccod").build();

        searchRequest.setOrganisationId("org-id");
        searchRequest.setStatus(List.of(TxValidationStatus.VALIDATED));
        searchRequest.setTransactionType(List.of(TransactionType.CardCharge));

        transactionEntity.setId("tx-id");
        transactionEntity.setTransactionType(TransactionType.CardCharge);
        transactionEntity.setAutomatedValidationStatus(TxValidationStatus.VALIDATED);
        transactionEntity.setTransactionApproved(Boolean.TRUE);
        transactionEntity.setLedgerDispatchApproved(Boolean.FALSE);
        transactionEntity.setOverallStatus(TransactionStatus.NOK);

        transactionEntity3.setId("tx-id-3");
        transactionEntity3.setTransactionType(TransactionType.CustomerPayment);
        transactionEntity3.setAutomatedValidationStatus(TxValidationStatus.VALIDATED);
        transactionEntity3.setTransactionApproved(Boolean.TRUE);
        transactionEntity3.setLedgerDispatchApproved(Boolean.TRUE);
        transactionEntity3.setOverallStatus(TransactionStatus.OK);

        transactionItem.setId("tx-item-id");
        transactionItem3.setId("tx-item-id-3");

        transactionViolation.setTxItemId(Optional.of(transactionItem.getId().toString()));
        transactionViolation.setSource(Source.ERP);
        transactionViolation.setSeverity(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.WARN);
        transactionViolation.setCode(CORE_CURRENCY_NOT_FOUND);

        transactionEntity.setItems(Set.of(transactionItem));
        transactionEntity3.setItems(Set.of(transactionItem3));
        transactionEntity.setViolations(Set.of(transactionViolation));

        transactionItem.setAccountDebit(Optional.of(accountDebit));
        transactionItem.setAccountCredit(Optional.of(accountCredit));
        transactionItem.setTransaction(transactionEntity);
        transactionItem.setAmountFcy(BigDecimal.valueOf(1000));
        transactionItem.setAmountLcy(BigDecimal.valueOf(1000));

        transactionItem3.setAccountDebit(Optional.of(accountDebit));
        transactionItem3.setAccountCredit(Optional.of(accountCredit));
        transactionItem3.setTransaction(transactionEntity);
        transactionItem3.setAmountFcy(BigDecimal.valueOf(500));
        transactionItem3.setAmountLcy(BigDecimal.valueOf(500));


        transactionEntity2.setId("tx-id2");
        transactionEntity2.setTransactionInternalNumber("tx-id2-internal");
        LocalDate localDate = LocalDate.now();
        transactionEntity2.setEntryDate(localDate);
        transactionEntity2.setTransactionType(TransactionType.CardCharge);
        transactionEntity2.setAutomatedValidationStatus(TxValidationStatus.FAILED);
        transactionEntity2.setTransactionApproved(Boolean.FALSE);
        transactionEntity2.setLedgerDispatchApproved(Boolean.TRUE);
        transactionEntity2.setOverallStatus(TransactionStatus.OK);

        when(transactionRepositoryGateway.findAllByStatus(any(), any(), any(), any())).thenReturn(List.of(transactionEntity, transactionEntity2, transactionEntity3));

        List<TransactionView> result = accountingCorePresentationConverter.allTransactions(searchRequest);

        assertEquals(3, result.size());
        assertEquals("tx-id", result.get(0).getId());
        assertEquals(TransactionType.CardCharge, result.get(0).getTransactionType());
        assertEquals(Boolean.TRUE, result.get(0).isTransactionApproved());
        assertEquals(Boolean.FALSE, result.get(0).isLedgerDispatchApproved());
        assertEquals(TransactionStatus.NOK, result.get(0).getStatus());
        assertEquals(Boolean.FALSE, result.get(2).isTransactionApproved());
        assertEquals(Boolean.TRUE, result.get(2).isLedgerDispatchApproved());
        assertEquals(TxValidationStatus.FAILED, result.get(2).getValidationStatus());
        assertEquals("tx-id2-internal", result.get(2).getInternalTransactionNumber());
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        assertEquals(LedgerDispatchStatusView.PUBLISHED, result.get(1).getStatistic());
        assertEquals(LedgerDispatchStatusView.PUBLISH, result.get(0).getStatistic());
        assertEquals(localDate.format(formatter).toString(), result.get(2).getEntryDate().toString());
        assertEquals("tx-item-id", result.get(0).getItems().stream().findFirst().get().getId());

        assertEquals(BigDecimal.valueOf(1000), result.get(0).getItems().stream().findFirst().get().getAmountFcy());
        assertEquals(BigDecimal.valueOf(1000), result.get(0).getItems().stream().findFirst().get().getAmountLcy());
        assertEquals(BigDecimal.valueOf(500), result.get(1).getItems().stream().findFirst().get().getAmountFcy());
        assertEquals(BigDecimal.valueOf(500), result.get(1).getItems().stream().findFirst().get().getAmountLcy());
        assertEquals("debit-code", result.get(0).getItems().stream().findFirst().get().getAccountDebitCode());
        assertEquals("credit-code", result.get(0).getItems().stream().findFirst().get().getAccountCreditCode());

        assertEquals(Optional.of("tx-item-id"), result.get(0).getViolations().stream().findFirst().get().getTransactionItemId());

        assertEquals(Source.ERP, result.get(0).getViolations().stream().findFirst().get().getSource());
        assertEquals(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.WARN, result.get(0).getViolations().stream().findFirst().get().getSeverity());
        assertEquals(CORE_CURRENCY_NOT_FOUND, result.get(0).getViolations().stream().findFirst().get().getCode());
    }

    @Test
    void testTransactionDetailSpecific() {

        String transactionId = "tx-id";
        TransactionEntity transactionEntity = new TransactionEntity();
        transactionEntity.setTransactionType(TransactionType.VendorBill);
        transactionEntity.setId(transactionId);

        when(transactionRepositoryGateway.findById(transactionId)).thenReturn(Optional.of(transactionEntity));

        Optional<TransactionView> result = accountingCorePresentationConverter.transactionDetailSpecific(transactionId);

        assertEquals(true, result.isPresent());
        assertEquals(transactionId, result.get().getId());
    }

    @Test
    void testBatchDetail() {

        org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation organisation = mock(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.class);
        when(organisation.getId()).thenReturn("123");
        TransactionItemEntity transactionItem = new TransactionItemEntity();
        transactionItem.setId("txItemId");
        transactionItem.setAmountLcy(BigDecimal.valueOf(100));
        transactionItem.setAmountFcy(BigDecimal.valueOf(100));
        transactionItem.setOperationType(OperationType.DEBIT);
        TransactionViolation transactionViolation = new TransactionViolation();
        transactionViolation.setTxItemId(Optional.of("txItemId"));
        LocalDate from = LocalDate.now();
        LocalDate to = LocalDate.now();
        FilteringParameters filteringParameters = new FilteringParameters("pros", List.of(TransactionType.CardCharge), from, to, LocalDate.now(), LocalDate.now(), Collections.singletonList("somestring"));

        String batchId = "batch-id";
        TransactionBatchEntity transactionBatchEntity = new TransactionBatchEntity();
        BatchStatistics batchStatistics = BatchStatistics.builder().total(10).pendingTransactions(1).invalidTransactions(8).approvedTransactions(5).publishedTransactions(6).readyToApproveTransactions(10).build();

        TransactionEntity transaction1 = new TransactionEntity();
        transaction1.setOrganisation(organisation);
        transaction1.setId("tx-id1");
        transaction1.setTransactionType(TransactionType.Journal);
        TransactionEntity transaction2 = new TransactionEntity();
        transaction2.setOrganisation(organisation);
        transaction2.setId("tx-id2");
        transaction2.setTransactionType(TransactionType.VendorBill);

        transaction1.setItems(Set.of(transactionItem));
        transaction1.setViolations(Set.of(transactionViolation));
        transactionItem.setTransaction(transaction1);

        transactionBatchEntity.setBatchStatistics(batchStatistics);
        transactionBatchEntity.setId(batchId);
        transactionBatchEntity.setCreatedAt(LocalDateTime.now());
        transactionBatchEntity.setUpdatedAt(LocalDateTime.now());
        transactionBatchEntity.setFilteringParameters(filteringParameters);
        Set<TransactionEntity> transactions = new LinkedHashSet<>();
        transactions.add(transaction1);
        transactions.add(transaction2);
        transactionBatchEntity.setTransactions(transactions);
        transactionBatchEntity.setStatus(TransactionBatchStatus.CREATED);
        transactionBatchEntity.setBatchStatistics(BatchStatistics.builder()
                .readyToApproveTransactions(2)
                .total(2).build());

        transaction1.setBatchId(batchId);
        transaction2.setBatchId(batchId);

        when(transactionBatchRepositoryGateway.findById(batchId)).thenReturn(Optional.of(transactionBatchEntity));
        when(transactionRepository.findAllByBatchId(batchId, null, Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(transaction1, transaction2)));

        Optional<BatchView> result = accountingCorePresentationConverter.batchDetail(batchId, null, Pageable.unpaged());

        assertEquals(true, result.isPresent());
        assertEquals(batchId, result.get().getId());
        assertEquals(2, result.get().getBatchStatistics().getTotal());
        assertEquals(0, result.get().getBatchStatistics().getInvalid());
        assertEquals(0, result.get().getBatchStatistics().getPublish());
        assertEquals(0, result.get().getBatchStatistics().getPending());
        assertEquals(0, result.get().getBatchStatistics().getPublished());
        assertEquals(2, result.get().getBatchStatistics().getApprove());
        assertEquals(2, result.get().getTransactions().stream().count());

        TransactionView resultTx1 = result.get().getTransactions().stream().filter(
                transactionView -> transactionView.getId() == "tx-id1"
        ).findFirst().get();

        assertEquals("tx-id1", resultTx1.getId());
        LocalDate today = LocalDate.now(clock);

        assertEquals(today, result.get().getFilteringParameters().getAccountingPeriodFrom());
        assertEquals(today, result.get().getFilteringParameters().getAccountingPeriodTo());
        assertEquals(from, result.get().getFilteringParameters().getFrom());
        assertEquals(to, result.get().getFilteringParameters().getTo());
        assertEquals(List.of(TransactionType.CardCharge), result.get().getFilteringParameters().getTransactionTypes());
        assertEquals(List.of("somestring"), result.get().getFilteringParameters().getTransactionNumbers());
        assertEquals("txItemId", resultTx1.getItems().stream().findFirst().get().getId());
        assertEquals("txItemId", resultTx1.getViolations().stream().findFirst().get().getTransactionItemId().get());
    }

    @Test
    void testListAllBatchModel() {

        BatchSearchRequest batchSearchRequest = new BatchSearchRequest();
        batchSearchRequest.setOrganisationId("org-id");
        batchSearchRequest.setFrom(LocalDate.now(clock));
        batchSearchRequest.setTransactionTypes(Set.of(TransactionType.CardCharge));
        FilteringParameters filteringParameters = new FilteringParameters("pros", List.of(TransactionType.CardCharge), LocalDate.now(clock), LocalDate.now(clock), LocalDate.now(clock), LocalDate.now(clock), Collections.singletonList("batch"));
        TransactionBatchEntity transactionBatchEntity = new TransactionBatchEntity();
        BatchStatistics batchStatistics = BatchStatistics.builder().total(10).invalidTransactions(1).pendingTransactions(9).publishedTransactions(10).build();
        transactionBatchEntity.setId("batch-id");
        transactionBatchEntity.setCreatedAt(LocalDateTime.now());
        transactionBatchEntity.setUpdatedAt(LocalDateTime.now());
        transactionBatchEntity.setFilteringParameters(filteringParameters);
        transactionBatchEntity.setBatchStatistics(batchStatistics);

        when(transactionBatchRepositoryGateway.findByFilter(batchSearchRequest)).thenReturn(List.of(transactionBatchEntity));
        when(transactionBatchRepositoryGateway.findByFilterCount(batchSearchRequest)).thenReturn(Long.valueOf(1));

        BatchsDetailView batchsDetailView = accountingCorePresentationConverter.listAllBatch(batchSearchRequest);
        List<BatchView> result = batchsDetailView.getBatchs();

        assertEquals(1, result.size());
        assertEquals(1, batchsDetailView.getTotal());
        assertEquals(1, batchsDetailView.getBatchs().stream().count());
        assertEquals("batch-id", result.iterator().next().getId());
        assertEquals(TransactionBatchStatus.CREATED, result.iterator().next().getStatus());

    }

    @Test
    void testExtractionTrigger() {

        ExtractionRequest extractionRequest = new ExtractionRequest();
        extractionRequest.setDateFrom("2022-01-01");
        extractionRequest.setDateTo("2022-12-31");
        extractionRequest.setOrganisationId("org-id");
        extractionRequest.setTransactionType(List.of(TransactionType.CardCharge));
        extractionRequest.setTransactionNumbers(List.of("num1", "num2"));

        accountingCorePresentationConverter.extractionTrigger(extractionRequest);
        Mockito.verify(accountingCoreService, Mockito.times(1)).scheduleIngestion(Mockito.any(UserExtractionParameters.class), eq(ExtractorType.NETSUITE), eq(null), eq(Map.of()));

    }

    @Test
    void testBatchReprocess() {

        Mockito.when(accountingCoreService.scheduleReIngestionForFailed("extractionRequest")).thenReturn(Either.right(null));
        accountingCorePresentationConverter.scheduleReIngestionForFailed("extractionRequest");
        Mockito.verify(accountingCoreService, Mockito.times(1)).scheduleReIngestionForFailed("extractionRequest");

    }

    @Test
    void testBatchReprocessNoExist() {

        Mockito.when(accountingCoreService.scheduleReIngestionForFailed("extractionRequest")).thenReturn(Either.left(Problem.builder()
                .withTitle("TX_BATCH_NOT_FOUND")
                .withDetail("Transaction batch with id: extractionRequest not found")
                .withStatus(Status.NOT_FOUND)
                .build()));
        accountingCorePresentationConverter.scheduleReIngestionForFailed("extractionRequest");
        Mockito.verify(accountingCoreService, Mockito.times(1)).scheduleReIngestionForFailed("extractionRequest");
    }

    @Test
    void allTransactionsDispatchStatus() {
        TransactionEntity transaction = new TransactionEntity();
        TransactionViolation transactionViolation = new TransactionViolation();
        transactionViolation.setSource(Source.LOB);
        TransactionItemEntity transactionItem = new TransactionItemEntity();

        transaction.setViolations(Set.of(transactionViolation));
        transaction.setItems(Set.of(transactionItem));
        transaction.setAutomatedValidationStatus(TxValidationStatus.VALIDATED);
        transaction.setLedgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED);
        assertEquals(LedgerDispatchStatusView.APPROVE, accountingCorePresentationConverter.getTransactionDispatchStatus(transaction));

        transaction.setTransactionApproved(true);

        assertEquals(LedgerDispatchStatusView.PUBLISH, accountingCorePresentationConverter.getTransactionDispatchStatus(transaction));

        transaction.setLedgerDispatchApproved(true);
        transaction.setLedgerDispatchStatus(LedgerDispatchStatus.MARK_DISPATCH);
        assertEquals(LedgerDispatchStatusView.PUBLISHED, accountingCorePresentationConverter.getTransactionDispatchStatus(transaction));

        transaction.setLedgerDispatchStatus(LedgerDispatchStatus.DISPATCHED);
        assertEquals(LedgerDispatchStatusView.PUBLISHED, accountingCorePresentationConverter.getTransactionDispatchStatus(transaction));

        transaction.setLedgerDispatchStatus(LedgerDispatchStatus.COMPLETED);
        assertEquals(LedgerDispatchStatusView.PUBLISHED, accountingCorePresentationConverter.getTransactionDispatchStatus(transaction));

        transaction.setLedgerDispatchStatus(LedgerDispatchStatus.FINALIZED);
        assertEquals(LedgerDispatchStatusView.PUBLISHED, accountingCorePresentationConverter.getTransactionDispatchStatus(transaction));

        transaction.setLedgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED);
        transactionItem.setRejection(Optional.of(new Rejection(RejectionReason.INCORRECT_AMOUNT)));
        transaction.setOverallStatus(TransactionStatus.NOK);
        assertEquals(LedgerDispatchStatusView.INVALID, accountingCorePresentationConverter.getTransactionDispatchStatus(transaction));

        transaction.setAutomatedValidationStatus(TxValidationStatus.FAILED);
        assertEquals(LedgerDispatchStatusView.INVALID, accountingCorePresentationConverter.getTransactionDispatchStatus(transaction));

        transaction.setLedgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED);
        transactionItem.setRejection(Optional.of(new Rejection(RejectionReason.REVIEW_PARENT_PROJECT_CODE)));
        assertEquals(LedgerDispatchStatusView.PENDING, accountingCorePresentationConverter.getTransactionDispatchStatus(transaction));

        transaction.setLedgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED);
        transactionItem.setRejection(Optional.empty());
        assertEquals(LedgerDispatchStatusView.PENDING, accountingCorePresentationConverter.getTransactionDispatchStatus(transaction));

        transactionItem.setRejection(Optional.of(new Rejection(RejectionReason.INCORRECT_PROJECT)));
        assertEquals(LedgerDispatchStatusView.INVALID, accountingCorePresentationConverter.getTransactionDispatchStatus(transaction));

        transactionViolation.setSource(Source.ERP);
        assertEquals(LedgerDispatchStatusView.INVALID, accountingCorePresentationConverter.getTransactionDispatchStatus(transaction));

        transaction.setAutomatedValidationStatus(TxValidationStatus.VALIDATED);
        transactionItem.setRejection(Optional.of(new Rejection(RejectionReason.REVIEW_PARENT_COST_CENTER)));
        transaction.setViolations(Set.of());
        assertEquals(LedgerDispatchStatusView.PENDING, accountingCorePresentationConverter.getTransactionDispatchStatus(transaction));
    }

}
