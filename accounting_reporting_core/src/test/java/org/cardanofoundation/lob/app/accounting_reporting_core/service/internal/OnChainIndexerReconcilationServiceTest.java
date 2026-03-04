package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OnChainTransactionDto;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OnChainTransactionItemDto;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Project;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.IndexerReconcilationServiceIF.IndexerReconcilationResult;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.IndexerTransactionTransformer.TransformedTransaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.IndexerTransactionTransformer.TransformedTransactionItem;

@ExtendWith(MockitoExtension.class)
class OnChainIndexerReconcilationServiceTest {

    @Mock
    private OnChainIndexerService indexerService;

    @Mock
    private IndexerTransactionTransformer indexerTransactionTransformer;

    private OnChainIndexerReconcilationService service;

    @BeforeEach
    void setUp() {
        service = new OnChainIndexerReconcilationService(indexerService, indexerTransactionTransformer);
    }

    @Test
    void reconcileWithIndexer_shouldReturnOK_whenTransactionsMatch() {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransactionItemEntity dbItem = createDbTransactionItem("item-1", BigDecimal.valueOf(1000), "1.0", "9000", "PRJ001");
        dbItem.setTransaction(dbTx);
        dbTx.setAllItems(Set.of(dbItem));

        // Mock transformer to return matching transformed transaction
        TransformedTransactionItem transformedItem = TransformedTransactionItem.builder()
                .id("item-1")
                .amountFcy(BigDecimal.valueOf(1000))
                .fxRate(BigDecimal.ONE)
                .costCenter(new IndexerTransactionTransformer.CostCenterHolder("9000", "Internal"))
                .project(new IndexerTransactionTransformer.ProjectHolder("PRJ001", "Test Project"))
                .document(new IndexerTransactionTransformer.DocumentHolder("doc-001",
                        new IndexerTransactionTransformer.CurrencyHolder("ISO_4217:CHF", "CHF"),
                        new IndexerTransactionTransformer.VatHolder("VAT0", BigDecimal.ZERO),
                        null))
                .accountEvent(new IndexerTransactionTransformer.AccountEventHolder("7820T000", "Test Event"))
                .operationType(OperationType.DEBIT)
                .build();

        TransformedTransaction transformedTx = TransformedTransaction.builder()
                .id("tx-1")
                .internalNumber("VENDPYMT-001")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId(organisationId)
                .items(List.of(transformedItem))
                .build();

        when(indexerTransactionTransformer.transformForIndexerComparison(any(TransactionEntity.class)))
                .thenReturn(transformedTx);

        OnChainTransactionItemDto indexerItem = new OnChainTransactionItemDto(
                "item-1", BigDecimal.valueOf(1000), "1", "doc-001", "ISO_4217:CHF",
                "Internal", "9000", "0", "VAT0", "7820T000", "Test Event", "PRJ001", "Test Project"
        );
        OnChainTransactionDto indexerTx = new OnChainTransactionDto(
                "tx-1", "hash-1", "VENDPYMT-001", "2024-01", "batch-1",
                "VendorPayment", "2024-01-15", organisationId, List.of(indexerItem)
        );

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        // When
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // Then
        assertThat(result.isRight()).isTrue();
        Map<String, IndexerReconcilationResult> results = result.get();
        assertThat(results).hasSize(1);
        assertThat(results.get("tx-1").status()).isEqualTo(ReconcilationCode.OK);
    }

    @Test
    void reconcileWithIndexer_shouldReturnNOK_whenTransactionNotFoundInIndexer() {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of())); // Empty list

        // When
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // Then
        assertThat(result.isRight()).isTrue();
        Map<String, IndexerReconcilationResult> results = result.get();
        assertThat(results).hasSize(1);
        assertThat(results.get("tx-1").status()).isEqualTo(ReconcilationCode.NOK);
        assertThat(results.get("tx-1").mismatchReason()).contains("not found in indexer");
    }

    @Test
    void reconcileWithIndexer_shouldReturnNOK_whenTransactionTypeMismatch() {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        // Mock transformer
        TransformedTransaction transformedTx = TransformedTransaction.builder()
                .id("tx-1")
                .internalNumber("VENDPYMT-001")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId(organisationId)
                .items(List.of())
                .build();

        when(indexerTransactionTransformer.transformForIndexerComparison(any(TransactionEntity.class)))
                .thenReturn(transformedTx);

        OnChainTransactionDto indexerTx = new OnChainTransactionDto(
                "tx-1", "hash-1", "VENDPYMT-001", "2024-01", "batch-1",
                "Journal", "2024-01-15", organisationId, List.of() // Different type
        );

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        // When
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // Then
        assertThat(result.isRight()).isTrue();
        Map<String, IndexerReconcilationResult> results = result.get();
        assertThat(results.get("tx-1").status()).isEqualTo(ReconcilationCode.NOK);
        assertThat(results.get("tx-1").mismatchReason()).contains("Type mismatch");
    }

    @Test
    void reconcileWithIndexer_shouldReturnNOK_whenItemsCountMismatch() {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransactionItemEntity dbItem = createDbTransactionItem("item-1", BigDecimal.valueOf(1000), "1.0", "9000", "PRJ001");
        dbItem.setTransaction(dbTx);
        dbTx.setAllItems(Set.of(dbItem));

        // Mock transformer to return one item
        TransformedTransactionItem transformedItem = TransformedTransactionItem.builder()
                .id("item-1")
                .amountFcy(BigDecimal.valueOf(1000))
                .build();

        TransformedTransaction transformedTx = TransformedTransaction.builder()
                .id("tx-1")
                .internalNumber("VENDPYMT-001")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId(organisationId)
                .items(List.of(transformedItem))
                .build();

        when(indexerTransactionTransformer.transformForIndexerComparison(any(TransactionEntity.class)))
                .thenReturn(transformedTx);

        // Indexer has no items
        OnChainTransactionDto indexerTx = new OnChainTransactionDto(
                "tx-1", "hash-1", "VENDPYMT-001", "2024-01", "batch-1",
                "VendorPayment", "2024-01-15", organisationId, List.of()
        );

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        // When
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // Then
        assertThat(result.isRight()).isTrue();
        Map<String, IndexerReconcilationResult> results = result.get();
        assertThat(results.get("tx-1").status()).isEqualTo(ReconcilationCode.NOK);
        assertThat(results.get("tx-1").mismatchReason()).contains("Items count mismatch");
    }

    @Test
    void reconcileWithIndexer_shouldReturnProblem_whenIndexerCallFails() {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Connection refused");
        problem.setTitle("INDEXER_API_ERROR");

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.left(problem));

        // When
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // Then
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("INDEXER_API_ERROR");
    }

    @Test
    void reconcileWithIndexer_shouldDetectTransactionInIndexerButNotInDb() {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        // DB has tx-1, but indexer has both tx-1 and tx-2
        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        // Mock transformer
        TransformedTransaction transformedTx = TransformedTransaction.builder()
                .id("tx-1")
                .internalNumber("VENDPYMT-001")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId(organisationId)
                .items(List.of())
                .build();

        when(indexerTransactionTransformer.transformForIndexerComparison(any(TransactionEntity.class)))
                .thenReturn(transformedTx);

        OnChainTransactionDto indexerTx1 = new OnChainTransactionDto(
                "tx-1", "hash-1", "VENDPYMT-001", "2024-01", "batch-1",
                "VendorPayment", "2024-01-15", organisationId, List.of()
        );
        OnChainTransactionDto indexerTx2 = new OnChainTransactionDto(
                "tx-2", "hash-2", "VENDPYMT-002", "2024-01", "batch-1",
                "VendorPayment", "2024-01-16", organisationId, List.of()
        );

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx1, indexerTx2)));

        // When
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // Then
        assertThat(result.isRight()).isTrue();
        Map<String, IndexerReconcilationResult> results = result.get();
        assertThat(results).hasSize(2);
        assertThat(results.get("tx-1").status()).isEqualTo(ReconcilationCode.OK);
        assertThat(results.get("tx-2").status()).isEqualTo(ReconcilationCode.NOK);
        assertThat(results.get("tx-2").mismatchReason()).contains("found in indexer but not in database");
    }

    @Test
    void reconcileWithIndexer_shouldDetectAmountMismatch() {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        // Mock transformer to return different amount - content fields must match indexer item for content-based matching
        TransformedTransactionItem transformedItem = TransformedTransactionItem.builder()
                .id("item-1")
                .amountFcy(BigDecimal.valueOf(2000)) // Different amount - this is what we're testing
                .fxRate(BigDecimal.ONE)
                .costCenter(new IndexerTransactionTransformer.CostCenterHolder("9000", "Internal"))
                .project(new IndexerTransactionTransformer.ProjectHolder("PRJ001", "Test Project"))
                .document(new IndexerTransactionTransformer.DocumentHolder("doc-001",
                        new IndexerTransactionTransformer.CurrencyHolder("ISO_4217:CHF", "CHF"),
                        new IndexerTransactionTransformer.VatHolder("VAT0", BigDecimal.ZERO),
                        null))
                .accountEvent(new IndexerTransactionTransformer.AccountEventHolder("7820T000", "Test Event"))
                .operationType(OperationType.DEBIT)
                .build();

        TransformedTransaction transformedTx = TransformedTransaction.builder()
                .id("tx-1")
                .internalNumber("VENDPYMT-001")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId(organisationId)
                .items(List.of(transformedItem))
                .build();

        when(indexerTransactionTransformer.transformForIndexerComparison(any(TransactionEntity.class)))
                .thenReturn(transformedTx);

        OnChainTransactionItemDto indexerItem = new OnChainTransactionItemDto(
                "item-1", BigDecimal.valueOf(1000), "1", "doc-001", "ISO_4217:CHF",
                "Internal", "9000", "0", "VAT0", "7820T000", "Test Event", "PRJ001", "Test Project"
        );
        OnChainTransactionDto indexerTx = new OnChainTransactionDto(
                "tx-1", "hash-1", "VENDPYMT-001", "2024-01", "batch-1",
                "VendorPayment", "2024-01-15", organisationId, List.of(indexerItem)
        );

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        // When
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // Then
        assertThat(result.isRight()).isTrue();
        Map<String, IndexerReconcilationResult> results = result.get();
        assertThat(results.get("tx-1").status()).isEqualTo(ReconcilationCode.NOK);
        assertThat(results.get("tx-1").mismatchReason()).contains("amountFcy mismatch");
    }

    @Test
    void reconcileWithIndexer_shouldNotCompareBatchId() {
        // Given - Batch ID comparison is currently disabled in the reconciliation service
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        // Mock transformer
        TransformedTransaction transformedTx = TransformedTransaction.builder()
                .id("tx-1")
                .internalNumber("VENDPYMT-001")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId(organisationId)
                .items(List.of())
                .build();

        when(indexerTransactionTransformer.transformForIndexerComparison(any(TransactionEntity.class)))
                .thenReturn(transformedTx);

        OnChainTransactionDto indexerTx = new OnChainTransactionDto(
                "tx-1", "hash-1", "VENDPYMT-001", "2024-01", "batch-2", // Different batch ID - but this should be ignored
                "VendorPayment", "2024-01-15", organisationId, List.of()
        );

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        // When
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // Then - Batch ID comparison is disabled, so this should be OK
        assertThat(result.isRight()).isTrue();
        Map<String, IndexerReconcilationResult> results = result.get();
        assertThat(results.get("tx-1").status()).isEqualTo(ReconcilationCode.OK);
    }

    @Test
    void reconcileWithIndexer_shouldDetectDateMismatch() {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        // Mock transformer
        TransformedTransaction transformedTx = TransformedTransaction.builder()
                .id("tx-1")
                .internalNumber("VENDPYMT-001")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId(organisationId)
                .items(List.of())
                .build();

        when(indexerTransactionTransformer.transformForIndexerComparison(any(TransactionEntity.class)))
                .thenReturn(transformedTx);

        OnChainTransactionDto indexerTx = new OnChainTransactionDto(
                "tx-1", "hash-1", "VENDPYMT-001", "2024-01", "batch-1",
                "VendorPayment", "2024-01-20", organisationId, List.of() // Different date
        );

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        // When
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // Then
        assertThat(result.isRight()).isTrue();
        Map<String, IndexerReconcilationResult> results = result.get();
        assertThat(results.get("tx-1").status()).isEqualTo(ReconcilationCode.NOK);
        assertThat(results.get("tx-1").mismatchReason()).contains("Date mismatch");
    }

    private TransactionEntity createDbTransaction(String id, String internalNumber, String type, String date, String batchId) {
        TransactionEntity tx = TransactionEntity.builder()
                .internalTransactionNumber(internalNumber)
                .transactionType(TransactionType.valueOf(type))
                .entryDate(LocalDate.parse(date))
                .batchId(batchId)
                .accountingPeriod(YearMonth.of(2024, 1))
                .organisation(Organisation.builder().id("test-org").build())
                .build();
        tx.setId(id);
        return tx;
    }

    @Test
    void reconcileWithIndexer_shouldHandleCreditOperationType() {
        // Given - CREDIT operations should negate the indexer amount for comparison
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransactionItemEntity dbItem = createDbTransactionItem("item-1", BigDecimal.valueOf(-1000), "1.0", "9000", "PRJ001");
        dbItem.setOperationType(OperationType.CREDIT); // Set CREDIT operation type
        dbItem.setTransaction(dbTx);
        dbTx.setAllItems(Set.of(dbItem));

        // Mock transformer to return CREDIT item - transformer already negates CREDIT amounts
        // (see IndexerTransactionTransformer line 109: amountFcy.negate() for non-DEBIT)
        // so DB -1000 becomes +1000 after transformation, matching the indexer's stored value
        TransformedTransactionItem transformedItem = TransformedTransactionItem.builder()
                .id("item-1")
                .amountFcy(BigDecimal.valueOf(1000)) // Positive after transformer negation of CREDIT -1000
                .fxRate(BigDecimal.ONE)
                .costCenter(new IndexerTransactionTransformer.CostCenterHolder("9000", "Internal"))
                .project(new IndexerTransactionTransformer.ProjectHolder("PRJ001", "Test Project"))
                .document(new IndexerTransactionTransformer.DocumentHolder("doc-001",
                        new IndexerTransactionTransformer.CurrencyHolder("ISO_4217:CHF", "CHF"),
                        new IndexerTransactionTransformer.VatHolder("VAT0", BigDecimal.ZERO),
                        null))
                .accountEvent(new IndexerTransactionTransformer.AccountEventHolder("7820T000", "Test Event"))
                .operationType(OperationType.CREDIT)
                .build();

        TransformedTransaction transformedTx = TransformedTransaction.builder()
                .id("tx-1")
                .internalNumber("VENDPYMT-001")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId(organisationId)
                .items(List.of(transformedItem))
                .build();

        when(indexerTransactionTransformer.transformForIndexerComparison(any(TransactionEntity.class)))
                .thenReturn(transformedTx);

        // Indexer stores positive amount, CREDIT should negate it for comparison
        OnChainTransactionItemDto indexerItem = new OnChainTransactionItemDto(
                "item-1", BigDecimal.valueOf(1000), "1", "doc-001", "ISO_4217:CHF",
                "Internal", "9000", "0", "VAT0", "7820T000", "Test Event", "PRJ001", "Test Project"
        );
        OnChainTransactionDto indexerTx = new OnChainTransactionDto(
                "tx-1", "hash-1", "VENDPYMT-001", "2024-01", "batch-1",
                "VendorPayment", "2024-01-15", organisationId, List.of(indexerItem)
        );

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        // When
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // Then - Transformer converts CREDIT -1000 to +1000, matching indexer's +1000
        assertThat(result.isRight()).isTrue();
        Map<String, IndexerReconcilationResult> results = result.get();
        assertThat(results.get("tx-1").status()).isEqualTo(ReconcilationCode.OK);
    }

    @Test
    void reconcileWithIndexer_shouldDetectFxRateMismatch() {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransactionItemEntity dbItem = createDbTransactionItem("item-1", BigDecimal.valueOf(1000), "1.25", "9000", "PRJ001");
        dbItem.setTransaction(dbTx);
        dbTx.setAllItems(Set.of(dbItem));

        // Mock transformer to return item with different fxRate - note: content key uses fxRate
        TransformedTransactionItem transformedItem = TransformedTransactionItem.builder()
                .id("item-1")
                .amountFcy(BigDecimal.valueOf(1000))
                .fxRate(new BigDecimal("1.25")) // Different from indexer
                .costCenter(new IndexerTransactionTransformer.CostCenterHolder("9000", "Internal"))
                .project(new IndexerTransactionTransformer.ProjectHolder("PRJ001", "Test Project"))
                .document(new IndexerTransactionTransformer.DocumentHolder("doc-001",
                        new IndexerTransactionTransformer.CurrencyHolder("ISO_4217:CHF", "CHF"),
                        new IndexerTransactionTransformer.VatHolder("VAT0", BigDecimal.ZERO),
                        null))
                .accountEvent(new IndexerTransactionTransformer.AccountEventHolder("7820T000", "Test Event"))
                .operationType(OperationType.DEBIT)
                .build();

        TransformedTransaction transformedTx = TransformedTransaction.builder()
                .id("tx-1")
                .internalNumber("VENDPYMT-001")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId(organisationId)
                .items(List.of(transformedItem))
                .build();

        when(indexerTransactionTransformer.transformForIndexerComparison(any(TransactionEntity.class)))
                .thenReturn(transformedTx);

        // Indexer has different fxRate - this will cause a mismatch because items won't match by content key
        OnChainTransactionItemDto indexerItem = new OnChainTransactionItemDto(
                "item-1", BigDecimal.valueOf(1000), "1.50", "doc-001", "ISO_4217:CHF",
                "Internal", "9000", "0", "VAT0", "7820T000", "Test Event", "PRJ001", "Test Project"
        );
        OnChainTransactionDto indexerTx = new OnChainTransactionDto(
                "tx-1", "hash-1", "VENDPYMT-001", "2024-01", "batch-1",
                "VendorPayment", "2024-01-15", organisationId, List.of(indexerItem)
        );

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        // When
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // Then - fxRate mismatch should cause item not found
        assertThat(result.isRight()).isTrue();
        Map<String, IndexerReconcilationResult> results = result.get();
        assertThat(results.get("tx-1").status()).isEqualTo(ReconcilationCode.NOK);
        assertThat(results.get("tx-1").mismatchReason()).contains("not found in indexer");
    }

    @Test
    void reconcileWithIndexer_shouldHandleInternalNumberMismatch() {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        // Mock transformer
        TransformedTransaction transformedTx = TransformedTransaction.builder()
                .id("tx-1")
                .internalNumber("VENDPYMT-001")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId(organisationId)
                .items(List.of())
                .build();

        when(indexerTransactionTransformer.transformForIndexerComparison(any(TransactionEntity.class)))
                .thenReturn(transformedTx);

        // Indexer has different internal number
        OnChainTransactionDto indexerTx = new OnChainTransactionDto(
                "tx-1", "hash-1", "VENDPYMT-999", "2024-01", "batch-1",
                "VendorPayment", "2024-01-15", organisationId, List.of()
        );

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        // When
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // Then
        assertThat(result.isRight()).isTrue();
        Map<String, IndexerReconcilationResult> results = result.get();
        assertThat(results.get("tx-1").status()).isEqualTo(ReconcilationCode.NOK);
        assertThat(results.get("tx-1").mismatchReason()).contains("Internal number mismatch");
    }

    @Test
    void reconcileWithIndexer_shouldHandleEmptyDbTransactions() {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        // Indexer has transactions but DB doesn't
        OnChainTransactionDto indexerTx = new OnChainTransactionDto(
                "tx-1", "hash-1", "VENDPYMT-001", "2024-01", "batch-1",
                "VendorPayment", "2024-01-15", organisationId, List.of()
        );

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        // When
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of());

        // Then - should detect orphaned indexer transaction
        assertThat(result.isRight()).isTrue();
        Map<String, IndexerReconcilationResult> results = result.get();
        assertThat(results).hasSize(1);
        assertThat(results.get("tx-1").status()).isEqualTo(ReconcilationCode.NOK);
        assertThat(results.get("tx-1").mismatchReason()).contains("found in indexer but not in database");
    }

    // ============== NEW: Caching tests (LOB-1332) ==============

    @Test
    void reconcileWithIndexer_shouldUseCachedResultForSameDateRange() {
        // Given - same organisationId + dateRange on two consecutive calls
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        TransformedTransaction transformedTx = TransformedTransaction.builder()
                .id("tx-1")
                .internalNumber("VENDPYMT-001")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId(organisationId)
                .items(List.of())
                .build();

        when(indexerTransactionTransformer.transformForIndexerComparison(any(TransactionEntity.class)))
                .thenReturn(transformedTx);

        OnChainTransactionDto indexerTx = new OnChainTransactionDto(
                "tx-1", "hash-1", "VENDPYMT-001", "2024-01", "batch-1",
                "VendorPayment", "2024-01-15", organisationId, List.of()
        );

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        // When - two consecutive calls with the same key
        service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));
        service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // Then - the indexer API should only be called once (second call uses cache)
        verify(indexerService, times(1)).retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo);
    }

    @Test
    void reconcileWithIndexer_shouldFetchFreshDataForDifferentDateRange() {
        // Given - different date ranges on two consecutive calls
        String organisationId = "test-org";
        LocalDate dateFromFirst = LocalDate.of(2024, 1, 1);
        LocalDate dateToFirst = LocalDate.of(2024, 1, 31);
        LocalDate dateFromSecond = LocalDate.of(2024, 2, 1);
        LocalDate dateToSecond = LocalDate.of(2024, 2, 29);

        TransactionEntity dbTx1 = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransactionEntity dbTx2 = createDbTransaction("tx-2", "VENDPYMT-002", "VendorPayment", "2024-02-15", "batch-1");

        TransformedTransaction transformedTx1 = TransformedTransaction.builder()
                .id("tx-1")
                .internalNumber("VENDPYMT-001")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId(organisationId)
                .items(List.of())
                .build();

        TransformedTransaction transformedTx2 = TransformedTransaction.builder()
                .id("tx-2")
                .internalNumber("VENDPYMT-002")
                .transactionType("VendorPayment")
                .entryDate("2024-02-15")
                .batchId("batch-1")
                .organisationId(organisationId)
                .items(List.of())
                .build();

        when(indexerTransactionTransformer.transformForIndexerComparison(any(TransactionEntity.class)))
                .thenAnswer(invocation -> {
                    TransactionEntity tx = invocation.getArgument(0);
                    return "tx-1".equals(tx.getId()) ? transformedTx1 : transformedTx2;
                });

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFromFirst, dateToFirst))
                .thenReturn(Either.right(List.of(new OnChainTransactionDto(
                        "tx-1", "hash-1", "VENDPYMT-001", "2024-01", "batch-1",
                        "VendorPayment", "2024-01-15", organisationId, List.of()
                ))));

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFromSecond, dateToSecond))
                .thenReturn(Either.right(List.of(new OnChainTransactionDto(
                        "tx-2", "hash-2", "VENDPYMT-002", "2024-02", "batch-1",
                        "VendorPayment", "2024-02-15", organisationId, List.of()
                ))));

        // When - two calls with different date ranges
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result1 =
                service.reconcileWithIndexer(organisationId, dateFromFirst, dateToFirst, Set.of(dbTx1));
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result2 =
                service.reconcileWithIndexer(organisationId, dateFromSecond, dateToSecond, Set.of(dbTx2));

        // Then - the indexer API is called twice (once per unique key)
        verify(indexerService, times(1)).retrieveTransactionsByDateRange(organisationId, dateFromFirst, dateToFirst);
        verify(indexerService, times(1)).retrieveTransactionsByDateRange(organisationId, dateFromSecond, dateToSecond);
        assertThat(result1.isRight()).isTrue();
        assertThat(result2.isRight()).isTrue();
    }

    @Test
    void reconcileWithIndexer_shouldUseCachedFailureForSameDateRange() {
        // Given - first call fails; second call with same key should use cached failure
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 3, 1);
        LocalDate dateTo = LocalDate.of(2024, 3, 31);

        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-03-15", "batch-1");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Indexer down");

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.left(problem));

        // When - two consecutive calls with the same key, first call returns error
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result1 =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result2 =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // Then - API called only once; second call uses cached (failed) result
        verify(indexerService, times(1)).retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo);
        assertThat(result1.isLeft()).isTrue();
        assertThat(result2.isLeft()).isTrue();
        assertThat(result2.getLeft().getDetail()).isEqualTo("Indexer down");
    }

    @Test
    void reconcileWithIndexer_shouldReturnOK_whenFxRateHasTrailingZerosNormalization() {
        // DB fxRate = new BigDecimal("1.0000"), indexer fxRate = "1"
        // Both normalize to "1" for content-key matching, and BigDecimal compareTo is also equal
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        TransformedTransactionItem transformedItem = TransformedTransactionItem.builder()
                .id("item-1")
                .amountFcy(BigDecimal.valueOf(1000))
                .fxRate(new BigDecimal("1.0000")) // trailing zeros
                .costCenter(new IndexerTransactionTransformer.CostCenterHolder("9000", "Internal"))
                .project(new IndexerTransactionTransformer.ProjectHolder("PRJ001", "Test Project"))
                .document(new IndexerTransactionTransformer.DocumentHolder("doc-001",
                        new IndexerTransactionTransformer.CurrencyHolder("ISO_4217:CHF", "CHF"),
                        new IndexerTransactionTransformer.VatHolder("VAT0", BigDecimal.ZERO),
                        null))
                .accountEvent(new IndexerTransactionTransformer.AccountEventHolder("7820T000", "Test Event"))
                .operationType(OperationType.DEBIT)
                .build();

        TransformedTransaction transformedTx = TransformedTransaction.builder()
                .id("tx-1")
                .internalNumber("VENDPYMT-001")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId(organisationId)
                .items(List.of(transformedItem))
                .build();

        when(indexerTransactionTransformer.transformForIndexerComparison(any(TransactionEntity.class)))
                .thenReturn(transformedTx);

        // Indexer stores fxRate as "1" (no trailing zeros)
        OnChainTransactionItemDto indexerItem = new OnChainTransactionItemDto(
                "item-1", BigDecimal.valueOf(1000), "1", "doc-001", "ISO_4217:CHF",
                "Internal", "9000", "0", "VAT0", "7820T000", "Test Event", "PRJ001", "Test Project"
        );
        OnChainTransactionDto indexerTx = new OnChainTransactionDto(
                "tx-1", "hash-1", "VENDPYMT-001", "2024-01", "batch-1",
                "VendorPayment", "2024-01-15", organisationId, List.of(indexerItem)
        );

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().get("tx-1").status()).isEqualTo(ReconcilationCode.OK);
    }

    @Test
    void reconcileWithIndexer_shouldIgnoreNullAmountFcy() {
        // When DB item has null amountFcy, compareAmountFcy is skipped and the transaction is OK
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        // null amountFcy but content key fields match the indexer item exactly
        TransformedTransactionItem transformedItem = TransformedTransactionItem.builder()
                .id("item-1")
                .amountFcy(null)  // null → compareAmountFcy skips the comparison
                .fxRate(BigDecimal.ONE)
                .costCenter(new IndexerTransactionTransformer.CostCenterHolder("9000", "Internal"))
                .project(new IndexerTransactionTransformer.ProjectHolder("PRJ001", "Test Project"))
                .document(new IndexerTransactionTransformer.DocumentHolder("doc-001",
                        new IndexerTransactionTransformer.CurrencyHolder("ISO_4217:CHF", "CHF"),
                        new IndexerTransactionTransformer.VatHolder("VAT0", BigDecimal.ZERO),
                        null))
                .accountEvent(new IndexerTransactionTransformer.AccountEventHolder("7820T000", "Test Event"))
                .operationType(OperationType.DEBIT)
                .build();

        TransformedTransaction transformedTx = TransformedTransaction.builder()
                .id("tx-1")
                .internalNumber("VENDPYMT-001")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId(organisationId)
                .items(List.of(transformedItem))
                .build();

        when(indexerTransactionTransformer.transformForIndexerComparison(any(TransactionEntity.class)))
                .thenReturn(transformedTx);

        // Indexer item has a non-null amountFcy; content key matches so compareItemFields is called
        OnChainTransactionItemDto indexerItem = new OnChainTransactionItemDto(
                "item-1", BigDecimal.valueOf(9999), "1", "doc-001", "ISO_4217:CHF",
                "Internal", "9000", "0", "VAT0", "7820T000", "Test Event", "PRJ001", "Test Project"
        );
        OnChainTransactionDto indexerTx = new OnChainTransactionDto(
                "tx-1", "hash-1", "VENDPYMT-001", "2024-01", "batch-1",
                "VendorPayment", "2024-01-15", organisationId, List.of(indexerItem)
        );

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // null amountFcy on DB side → comparison skipped → no mismatch → OK
        assertThat(result.isRight()).isTrue();
        assertThat(result.get().get("tx-1").status()).isEqualTo(ReconcilationCode.OK);
    }

    @Test
    void reconcileWithIndexer_shouldFetchFreshDataForDifferentOrganisation() {
        // Same date range but different organisationId → cache key differs → two separate API calls
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTxOrg1 = createDbTransaction("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransactionEntity dbTxOrg2 = createDbTransaction("tx-2", "VENDPYMT-002", "VendorPayment", "2024-01-15", "batch-1");

        TransformedTransaction transformedTx = TransformedTransaction.builder()
                .id("tx-1")
                .internalNumber("VENDPYMT-001")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId("org-1")
                .items(List.of())
                .build();

        TransformedTransaction transformedTx2 = TransformedTransaction.builder()
                .id("tx-2")
                .internalNumber("VENDPYMT-002")
                .transactionType("VendorPayment")
                .entryDate("2024-01-15")
                .batchId("batch-1")
                .organisationId("org-2")
                .items(List.of())
                .build();

        when(indexerTransactionTransformer.transformForIndexerComparison(any(TransactionEntity.class)))
                .thenAnswer(invocation -> {
                    TransactionEntity tx = invocation.getArgument(0);
                    return "tx-1".equals(tx.getId()) ? transformedTx : transformedTx2;
                });

        when(indexerService.retrieveTransactionsByDateRange("org-1", dateFrom, dateTo))
                .thenReturn(Either.right(List.of(new OnChainTransactionDto(
                        "tx-1", "hash-1", "VENDPYMT-001", "2024-01", "batch-1",
                        "VendorPayment", "2024-01-15", "org-1", List.of()
                ))));
        when(indexerService.retrieveTransactionsByDateRange("org-2", dateFrom, dateTo))
                .thenReturn(Either.right(List.of(new OnChainTransactionDto(
                        "tx-2", "hash-2", "VENDPYMT-002", "2024-01", "batch-1",
                        "VendorPayment", "2024-01-15", "org-2", List.of()
                ))));

        service.reconcileWithIndexer("org-1", dateFrom, dateTo, Set.of(dbTxOrg1));
        service.reconcileWithIndexer("org-2", dateFrom, dateTo, Set.of(dbTxOrg2));

        // different org → different cache key → two independent API calls
        verify(indexerService, times(1)).retrieveTransactionsByDateRange("org-1", dateFrom, dateTo);
        verify(indexerService, times(1)).retrieveTransactionsByDateRange("org-2", dateFrom, dateTo);
    }

    private TransactionItemEntity createDbTransactionItem(String id, BigDecimal amount, String fxRate, String costCenterCode, String projectCode) {
        TransactionItemEntity item = new TransactionItemEntity();
        item.setId(id);
        item.setAmountFcy(amount);
        item.setAmountLcy(amount);
        item.setFxRate(new BigDecimal(fxRate));
        item.setOperationType(OperationType.DEBIT);
        item.setCostCenter(Optional.of(CostCenter.builder().customerCode(costCenterCode).build()));
        item.setProject(Optional.of(Project.builder().customerCode(projectCode).build()));
        return item;
    }
}
