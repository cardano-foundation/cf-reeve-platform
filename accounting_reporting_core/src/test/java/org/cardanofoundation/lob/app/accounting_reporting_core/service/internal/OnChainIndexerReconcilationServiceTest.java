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
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.IndexerReconcilationServiceIF.IndexerReconcilationResult;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.IndexerTransactionTransformer.TransformedTransaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.IndexerTransactionTransformer.TransformedTransactionItem;

@ExtendWith(MockitoExtension.class)
class OnChainIndexerReconcilationServiceTest {

    @Mock
    private OnChainIndexerService indexerService;

    @Mock
    private IndexerTransactionTransformer indexerTransactionTransformer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OnChainIndexerReconcilationService service;

    @BeforeEach
    void setUp() {
        service = new OnChainIndexerReconcilationService(indexerService, indexerTransactionTransformer, objectMapper);
    }

    // ============== Helper methods ==============

    private TransactionEntity buildDbTx(String id, String internalNumber, String type, String date, String batchId) {
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

    /**
     * Builds a TransformedTransactionItem whose content key matches the default indexer item
     * created by buildIndexerItem(). CurrencyHolder uses (customerCode="CHF", id="ISO_4217:CHF")
     * so that currency().id() == indexerItem.currency().
     */
    private TransformedTransactionItem buildTransformedItem(String id, BigDecimal amountFcy, String fxRate) {
        return TransformedTransactionItem.builder()
                .id(id)
                .amountFcy(amountFcy)
                .fxRate(new BigDecimal(fxRate))
                .costCenter(new IndexerTransactionTransformer.CostCenterHolder("9000", "Internal"))
                .project(new IndexerTransactionTransformer.ProjectHolder("PRJ001", "Test Project"))
                .document(new IndexerTransactionTransformer.DocumentHolder("doc-001",
                        new IndexerTransactionTransformer.CurrencyHolder("CHF", "ISO_4217:CHF"),
                        new IndexerTransactionTransformer.VatHolder("VAT0", BigDecimal.ZERO),
                        null))
                .accountEvent(new IndexerTransactionTransformer.AccountEventHolder("7820T000", "Test Event"))
                .operationType(OperationType.DEBIT)
                .build();
    }

    private TransformedTransaction buildTransformedTx(String id, String internalNumber, String type,
                                                       String date, String batchId, String orgId,
                                                       List<TransformedTransactionItem> items) {
        return TransformedTransaction.builder()
                .id(id)
                .internalNumber(internalNumber)
                .transactionType(type)
                .entryDate(date)
                .batchId(batchId)
                .organisationId(orgId)
                .items(items)
                .build();
    }

    private OnChainTransactionItemDto buildIndexerItem(String id, BigDecimal amountFcy, String fxRate) {
        return new OnChainTransactionItemDto(
                id, amountFcy, fxRate, "doc-001", "ISO_4217:CHF",
                "Internal", "9000", "0", "VAT0", "7820T000", "Test Event", "PRJ001", "Test Project"
        );
    }

    private OnChainTransactionDto buildIndexerTx(String id, String internalNumber, String type,
                                                  String date, String orgId,
                                                  List<OnChainTransactionItemDto> items) {
        return new OnChainTransactionDto(
                id, "hash-" + id, internalNumber, "2024-01", "batch-1",
                type, date, orgId, items
        );
    }

    private JsonNode parseDiff(String mismatchReason) throws Exception {
        return objectMapper.readTree(mismatchReason);
    }

    // ============== Transaction-level mismatch tests ==============

    @Test
    void shouldReturnOK_whenTransactionsMatch() {
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransformedTransactionItem item = buildTransformedItem("item-1", BigDecimal.valueOf(1000), "1");
        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId,
                List.of(item));

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);

        OnChainTransactionItemDto indexerItem = buildIndexerItem("item-1", BigDecimal.valueOf(1000), "1");
        OnChainTransactionDto indexerTx = buildIndexerTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", organisationId, List.of(indexerItem));

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get("tx-1").status()).isEqualTo(ReconcilationCode.OK);
        assertThat(result.get().get("tx-1").mismatchReason()).isNull();
    }

    @Test
    void shouldNotAddResult_whenDbTxNotFoundInIndexer() {
        // DB transaction that has no counterpart in the indexer → silently skipped, no result entry
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of()));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        // No entry added for a DB tx that is simply absent from the indexer
        assertThat(result.get()).isEmpty();
    }

    @Test
    void shouldReturnNOK_withTxNotInDb_whenTxFoundInIndexerButNotInDb() throws Exception {
        // Indexer has a transaction that is not in the DB → NOK with txNotInDb=true
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        OnChainTransactionDto indexerOrphan = buildIndexerTx(
                "tx-orphan", "VENDPYMT-999", "VendorPayment", "2024-01-15", organisationId, List.of());

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerOrphan)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of());

        assertThat(result.isRight()).isTrue();
        Map<String, IndexerReconcilationResult> results = result.get();
        assertThat(results).hasSize(1);
        assertThat(results.get("tx-orphan").status()).isEqualTo(ReconcilationCode.NOK);

        JsonNode diff = parseDiff(results.get("tx-orphan").mismatchReason());
        assertThat(diff.get("txNotInDb").asBoolean()).isTrue();
        assertThat(diff.has("txFieldMismatches")).isFalse();
        assertThat(diff.has("itemCountMismatch")).isFalse();
        assertThat(diff.has("itemDiffs")).isFalse();
    }

    @Test
    void shouldReturnNOK_whenTypeMismatch() throws Exception {
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId, List.of());

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);

        OnChainTransactionDto indexerTx = buildIndexerTx(
                "tx-1", "VENDPYMT-001", "Journal", "2024-01-15", organisationId, List.of()); // different type

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        IndexerReconcilationResult r = result.get().get("tx-1");
        assertThat(r.status()).isEqualTo(ReconcilationCode.NOK);

        JsonNode diff = parseDiff(r.mismatchReason());
        JsonNode mismatches = diff.get("txFieldMismatches");
        assertThat(mismatches).isNotNull();
        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.get(0).get("field").asText()).isEqualTo("type");
        assertThat(mismatches.get(0).get("reeve").asText()).isEqualTo("VendorPayment");
        assertThat(mismatches.get(0).get("indexer").asText()).isEqualTo("Journal");
    }

    @Test
    void shouldReturnNOK_whenDateMismatch() throws Exception {
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId, List.of());

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);

        OnChainTransactionDto indexerTx = buildIndexerTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-20", organisationId, List.of()); // different date

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        IndexerReconcilationResult r = result.get().get("tx-1");
        assertThat(r.status()).isEqualTo(ReconcilationCode.NOK);

        JsonNode diff = parseDiff(r.mismatchReason());
        JsonNode mismatches = diff.get("txFieldMismatches");
        assertThat(mismatches).isNotNull();
        assertThat(mismatches.get(0).get("field").asText()).isEqualTo("date");
        assertThat(mismatches.get(0).get("reeve").asText()).isEqualTo("2024-01-15");
        assertThat(mismatches.get(0).get("indexer").asText()).isEqualTo("2024-01-20");
    }

    @Test
    void shouldReturnNOK_whenInternalNumberMismatch() throws Exception {
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId, List.of());

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);

        OnChainTransactionDto indexerTx = buildIndexerTx(
                "tx-1", "VENDPYMT-999", "VendorPayment", "2024-01-15", organisationId, List.of()); // different number

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        IndexerReconcilationResult r = result.get().get("tx-1");
        assertThat(r.status()).isEqualTo(ReconcilationCode.NOK);

        JsonNode diff = parseDiff(r.mismatchReason());
        JsonNode mismatches = diff.get("txFieldMismatches");
        assertThat(mismatches).isNotNull();
        assertThat(mismatches.get(0).get("field").asText()).isEqualTo("internalNumber");
        assertThat(mismatches.get(0).get("reeve").asText()).isEqualTo("VENDPYMT-001");
        assertThat(mismatches.get(0).get("indexer").asText()).isEqualTo("VENDPYMT-999");
    }

    @Test
    void shouldReturnNOK_whenMultipleFieldsMismatch() throws Exception {
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId, List.of());

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);

        // type, date, and internalNumber all differ
        OnChainTransactionDto indexerTx = buildIndexerTx(
                "tx-1", "VENDPYMT-999", "Journal", "2024-01-20", organisationId, List.of());

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        IndexerReconcilationResult r = result.get().get("tx-1");
        assertThat(r.status()).isEqualTo(ReconcilationCode.NOK);

        JsonNode diff = parseDiff(r.mismatchReason());
        assertThat(diff.get("txFieldMismatches")).hasSize(3);
    }

    @Test
    void shouldNotCompareBatchId() {
        // Batch ID is not compared; a different batchId in indexer should not cause NOK
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId, List.of());

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);

        // Different batch ID in indexer
        OnChainTransactionDto indexerTx = new OnChainTransactionDto(
                "tx-1", "hash-tx-1", "VENDPYMT-001", "2024-01", "batch-DIFFERENT",
                "VendorPayment", "2024-01-15", organisationId, List.of());

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().get("tx-1").status()).isEqualTo(ReconcilationCode.OK);
    }

    // ============== Item count mismatch ==============

    @Test
    void shouldReturnNOK_whenItemCountMismatch() throws Exception {
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransformedTransactionItem item = buildTransformedItem("item-1", BigDecimal.valueOf(1000), "1");
        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId,
                List.of(item)); // 1 DB item

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);

        // Indexer has no items → count mismatch (1 vs 0)
        OnChainTransactionDto indexerTx = buildIndexerTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", organisationId, List.of());

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        IndexerReconcilationResult r = result.get().get("tx-1");
        assertThat(r.status()).isEqualTo(ReconcilationCode.NOK);

        JsonNode diff = parseDiff(r.mismatchReason());
        JsonNode countMismatch = diff.get("itemCountMismatch");
        assertThat(countMismatch).isNotNull();
        assertThat(countMismatch.get("reeve").asInt()).isEqualTo(1);
        assertThat(countMismatch.get("indexer").asInt()).isZero();
        assertThat(diff.has("itemDiffs")).isFalse();
    }

    // ============== Item-level mismatch tests ==============

    @Test
    void shouldReturnNOK_whenDbItemNotFoundInIndexer() throws Exception {
        // DB item has fxRate "1.25" but indexer item has fxRate "1.50" → content keys differ → item not found
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransformedTransactionItem item = buildTransformedItem("item-1", BigDecimal.valueOf(1000), "1.25");
        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId,
                List.of(item));

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);

        OnChainTransactionItemDto indexerItem = buildIndexerItem("item-x", BigDecimal.valueOf(1000), "1.50"); // different fxRate
        OnChainTransactionDto indexerTx = buildIndexerTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", organisationId, List.of(indexerItem));

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        IndexerReconcilationResult r = result.get().get("tx-1");
        assertThat(r.status()).isEqualTo(ReconcilationCode.NOK);

        JsonNode diff = parseDiff(r.mismatchReason());
        JsonNode itemDiffs = diff.get("itemDiffs");
        assertThat(itemDiffs).isNotNull();
        assertThat(itemDiffs).hasSize(1);
        JsonNode itemDiff = itemDiffs.get(0);
        assertThat(itemDiff.get("itemId").asText()).isEqualTo("item-1");
        assertThat(itemDiff.get("notFound").asBoolean()).isTrue();
        assertThat(itemDiff.has("dbItem")).isTrue();
        assertThat(itemDiff.has("indexerItems")).isTrue();
    }

    @Test
    void shouldIncludeOnlyUnmatchedIndexerItems_whenDbItemNotFound() throws Exception {
        // DB items: item-A (eventCode EVT1), item-B (eventCode EVT2)
        // Indexer items: item-X (eventCode EVT2, matches item-B), item-Y (eventCode EVT3, unmatched)
        // → item-A is not found; indexerItems for item-A should show only item-Y (unmatched), not item-X
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        // item-A: eventCode "EVT1" (no match in indexer)
        TransformedTransactionItem itemA = TransformedTransactionItem.builder()
                .id("item-A")
                .amountFcy(BigDecimal.valueOf(100))
                .fxRate(BigDecimal.ONE)
                .accountEvent(new IndexerTransactionTransformer.AccountEventHolder("EVT1", "Event 1"))
                .operationType(OperationType.DEBIT)
                .build();

        // item-B: eventCode "EVT2" (matches indexer item-X)
        TransformedTransactionItem itemB = TransformedTransactionItem.builder()
                .id("item-B")
                .amountFcy(BigDecimal.valueOf(200))
                .fxRate(BigDecimal.ONE)
                .accountEvent(new IndexerTransactionTransformer.AccountEventHolder("EVT2", "Event 2"))
                .operationType(OperationType.DEBIT)
                .build();

        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId,
                List.of(itemA, itemB));

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);

        // item-X: eventCode "EVT2" (matches item-B)
        OnChainTransactionItemDto indexerItemX = new OnChainTransactionItemDto(
                "item-X", BigDecimal.valueOf(200), "1", null, null,
                null, null, null, null, "EVT2", "Event 2", null, null);

        // item-Y: eventCode "EVT3" (unmatched — no DB item has EVT3)
        OnChainTransactionItemDto indexerItemY = new OnChainTransactionItemDto(
                "item-Y", BigDecimal.valueOf(300), "1", null, null,
                null, null, null, null, "EVT3", "Event 3", null, null);

        OnChainTransactionDto indexerTx = buildIndexerTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", organisationId,
                List.of(indexerItemX, indexerItemY));

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        IndexerReconcilationResult r = result.get().get("tx-1");
        assertThat(r.status()).isEqualTo(ReconcilationCode.NOK);

        JsonNode diff = parseDiff(r.mismatchReason());
        JsonNode itemDiffs = diff.get("itemDiffs");
        assertThat(itemDiffs).isNotNull();
        assertThat(itemDiffs).hasSize(1); // only item-A is not found; item-B matches

        JsonNode itemDiff = itemDiffs.get(0);
        assertThat(itemDiff.get("itemId").asText()).isEqualTo("item-A");
        assertThat(itemDiff.get("notFound").asBoolean()).isTrue();

        // indexerItems should contain only item-Y (unmatched), NOT item-X (which matched item-B)
        JsonNode indexerItems = itemDiff.get("indexerItems");
        assertThat(indexerItems).hasSize(1);
        assertThat(indexerItems.get(0).get("id").asText()).isEqualTo("item-Y");
    }

    @Test
    void shouldReturnNOK_whenAmountFcyMismatch() throws Exception {
        // Content-key fields match (same fxRate, costCenter, project, etc.) but amountFcy differs
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransformedTransactionItem item = buildTransformedItem("item-1", BigDecimal.valueOf(2000), "1"); // DB amount 2000
        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId,
                List.of(item));

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);

        OnChainTransactionItemDto indexerItem = buildIndexerItem("item-1", BigDecimal.valueOf(1000), "1"); // indexer amount 1000
        OnChainTransactionDto indexerTx = buildIndexerTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", organisationId, List.of(indexerItem));

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        IndexerReconcilationResult r = result.get().get("tx-1");
        assertThat(r.status()).isEqualTo(ReconcilationCode.NOK);

        JsonNode diff = parseDiff(r.mismatchReason());
        JsonNode itemDiffs = diff.get("itemDiffs");
        assertThat(itemDiffs).isNotNull();
        assertThat(itemDiffs).hasSize(1);

        JsonNode fieldMismatches = itemDiffs.get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull();
        assertThat(fieldMismatches).hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("amountFcy");
        assertThat(fieldMismatches.get(0).get("reeve").asText()).isEqualTo("2000");
        assertThat(fieldMismatches.get(0).get("indexer").asText()).isEqualTo("1000");
    }

    @Test
    void shouldReturnNOK_whenCurrencyMismatch() throws Exception {
        // Content-key fields match; currency ID in DB ("USD") differs from indexer ("ISO_4217:CHF")
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        // CurrencyHolder(customerCode="CHF", id="USD") → currency().id() = "USD"
        TransformedTransactionItem item = TransformedTransactionItem.builder()
                .id("item-1")
                .amountFcy(BigDecimal.valueOf(1000))
                .fxRate(BigDecimal.ONE)
                .costCenter(new IndexerTransactionTransformer.CostCenterHolder("9000", "Internal"))
                .project(new IndexerTransactionTransformer.ProjectHolder("PRJ001", "Test Project"))
                .document(new IndexerTransactionTransformer.DocumentHolder("doc-001",
                        new IndexerTransactionTransformer.CurrencyHolder("CHF", "USD"), // id="USD" differs
                        new IndexerTransactionTransformer.VatHolder("VAT0", BigDecimal.ZERO),
                        null))
                .accountEvent(new IndexerTransactionTransformer.AccountEventHolder("7820T000", "Test Event"))
                .operationType(OperationType.DEBIT)
                .build();

        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId,
                List.of(item));

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);

        // Indexer currency = "ISO_4217:CHF"
        OnChainTransactionItemDto indexerItem = buildIndexerItem("item-1", BigDecimal.valueOf(1000), "1");
        OnChainTransactionDto indexerTx = buildIndexerTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", organisationId, List.of(indexerItem));

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        IndexerReconcilationResult r = result.get().get("tx-1");
        assertThat(r.status()).isEqualTo(ReconcilationCode.NOK);

        JsonNode diff = parseDiff(r.mismatchReason());
        JsonNode fieldMismatches = diff.get("itemDiffs").get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull();
        assertThat(fieldMismatches).hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("currency");
        assertThat(fieldMismatches.get(0).get("reeve").asText()).isEqualTo("USD");
        assertThat(fieldMismatches.get(0).get("indexer").asText()).isEqualTo("ISO_4217:CHF");
    }

    @Test
    void shouldReturnNOK_whenFxRateDiffers_itemNotFound() throws Exception {
        // fxRate is in the content key, so a different fxRate means the item has no match → notFound
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransformedTransactionItem item = buildTransformedItem("item-1", BigDecimal.valueOf(1000), "1.25");
        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId,
                List.of(item));

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);

        // Indexer has fxRate "1.50" → different content key → no match
        OnChainTransactionItemDto indexerItem = buildIndexerItem("item-1", BigDecimal.valueOf(1000), "1.50");
        OnChainTransactionDto indexerTx = buildIndexerTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", organisationId, List.of(indexerItem));

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        IndexerReconcilationResult r = result.get().get("tx-1");
        assertThat(r.status()).isEqualTo(ReconcilationCode.NOK);

        JsonNode diff = parseDiff(r.mismatchReason());
        JsonNode itemDiff = diff.get("itemDiffs").get(0);
        assertThat(itemDiff.get("notFound").asBoolean()).isTrue();
        // no fieldMismatches because the item was not found (not matched for field comparison)
        assertThat(itemDiff.has("fieldMismatches")).isFalse();
    }

    @Test
    void shouldReturnOK_whenFxRateTrailingZerosNormalized() {
        // DB fxRate = "1.0000", indexer fxRate = "1" → both normalize to "1" for content key → match
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransformedTransactionItem item = buildTransformedItem("item-1", BigDecimal.valueOf(1000), "1.0000");
        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId,
                List.of(item));

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);

        OnChainTransactionItemDto indexerItem = buildIndexerItem("item-1", BigDecimal.valueOf(1000), "1"); // no trailing zeros
        OnChainTransactionDto indexerTx = buildIndexerTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", organisationId, List.of(indexerItem));

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().get("tx-1").status()).isEqualTo(ReconcilationCode.OK);
    }

    @Test
    void shouldReturnOK_whenNullAmountFcySkipsComparison() {
        // When DB item has null amountFcy, the amountFcy comparison is skipped (no mismatch)
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        // null amountFcy but content key fields match
        TransformedTransactionItem item = TransformedTransactionItem.builder()
                .id("item-1")
                .amountFcy(null) // null → skips comparison
                .fxRate(BigDecimal.ONE)
                .costCenter(new IndexerTransactionTransformer.CostCenterHolder("9000", "Internal"))
                .project(new IndexerTransactionTransformer.ProjectHolder("PRJ001", "Test Project"))
                .document(new IndexerTransactionTransformer.DocumentHolder("doc-001",
                        new IndexerTransactionTransformer.CurrencyHolder("CHF", "ISO_4217:CHF"),
                        new IndexerTransactionTransformer.VatHolder("VAT0", BigDecimal.ZERO),
                        null))
                .accountEvent(new IndexerTransactionTransformer.AccountEventHolder("7820T000", "Test Event"))
                .operationType(OperationType.DEBIT)
                .build();

        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId,
                List.of(item));

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);

        // Indexer has a different amountFcy, but null check on DB side skips the comparison
        OnChainTransactionItemDto indexerItem = buildIndexerItem("item-1", BigDecimal.valueOf(9999), "1");
        OnChainTransactionDto indexerTx = buildIndexerTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", organisationId, List.of(indexerItem));

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().get("tx-1").status()).isEqualTo(ReconcilationCode.OK);
    }

    @Test
    void shouldHandleCreditOperationType() {
        // CREDIT items are pre-negated by the transformer; the reconciler compares as-is
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        // Transformer already applied CREDIT negation: -1000 → +1000
        TransformedTransactionItem item = TransformedTransactionItem.builder()
                .id("item-1")
                .amountFcy(BigDecimal.valueOf(1000))
                .fxRate(BigDecimal.ONE)
                .costCenter(new IndexerTransactionTransformer.CostCenterHolder("9000", "Internal"))
                .project(new IndexerTransactionTransformer.ProjectHolder("PRJ001", "Test Project"))
                .document(new IndexerTransactionTransformer.DocumentHolder("doc-001",
                        new IndexerTransactionTransformer.CurrencyHolder("CHF", "ISO_4217:CHF"),
                        new IndexerTransactionTransformer.VatHolder("VAT0", BigDecimal.ZERO),
                        null))
                .accountEvent(new IndexerTransactionTransformer.AccountEventHolder("7820T000", "Test Event"))
                .operationType(OperationType.CREDIT)
                .build();

        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId,
                List.of(item));

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);

        OnChainTransactionItemDto indexerItem = buildIndexerItem("item-1", BigDecimal.valueOf(1000), "1");
        OnChainTransactionDto indexerTx = buildIndexerTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", organisationId, List.of(indexerItem));

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(indexerTx)));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().get("tx-1").status()).isEqualTo(ReconcilationCode.OK);
    }

    // ============== Error handling ==============

    @Test
    void shouldReturnProblem_whenIndexerCallFails() {
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Connection refused");
        problem.setTitle("INDEXER_API_ERROR");

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.left(problem));

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("INDEXER_API_ERROR");
    }

    // ============== Caching tests ==============

    @Test
    void shouldUseCachedResult_forSameDateRange() {
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransformedTransaction transformedTx = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId, List.of());

        when(indexerTransactionTransformer.transformForIndexerComparison(any())).thenReturn(transformedTx);
        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.right(List.of(buildIndexerTx(
                        "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", organisationId, List.of()))));

        service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));
        service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        // API called only once; second call uses cached result
        verify(indexerService, times(1)).retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo);
    }

    @Test
    void shouldFetchFreshData_forDifferentDateRange() {
        String organisationId = "test-org";
        LocalDate dateFromFirst = LocalDate.of(2024, 1, 1);
        LocalDate dateToFirst = LocalDate.of(2024, 1, 31);
        LocalDate dateFromSecond = LocalDate.of(2024, 2, 1);
        LocalDate dateToSecond = LocalDate.of(2024, 2, 29);

        TransactionEntity dbTx1 = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransactionEntity dbTx2 = buildDbTx("tx-2", "VENDPYMT-002", "VendorPayment", "2024-02-15", "batch-1");

        TransformedTransaction transformedTx1 = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", organisationId, List.of());
        TransformedTransaction transformedTx2 = buildTransformedTx(
                "tx-2", "VENDPYMT-002", "VendorPayment", "2024-02-15", "batch-1", organisationId, List.of());

        when(indexerTransactionTransformer.transformForIndexerComparison(any()))
                .thenAnswer(invocation -> {
                    TransactionEntity tx = invocation.getArgument(0);
                    return "tx-1".equals(tx.getId()) ? transformedTx1 : transformedTx2;
                });

        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFromFirst, dateToFirst))
                .thenReturn(Either.right(List.of(buildIndexerTx(
                        "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", organisationId, List.of()))));
        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFromSecond, dateToSecond))
                .thenReturn(Either.right(List.of(buildIndexerTx(
                        "tx-2", "VENDPYMT-002", "VendorPayment", "2024-02-15", organisationId, List.of()))));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result1 =
                service.reconcileWithIndexer(organisationId, dateFromFirst, dateToFirst, Set.of(dbTx1));
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result2 =
                service.reconcileWithIndexer(organisationId, dateFromSecond, dateToSecond, Set.of(dbTx2));

        verify(indexerService, times(1)).retrieveTransactionsByDateRange(organisationId, dateFromFirst, dateToFirst);
        verify(indexerService, times(1)).retrieveTransactionsByDateRange(organisationId, dateFromSecond, dateToSecond);
        assertThat(result1.isRight()).isTrue();
        assertThat(result2.isRight()).isTrue();
    }

    @Test
    void shouldFetchFreshData_forDifferentOrganisation() {
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        TransactionEntity dbTxOrg1 = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1");
        TransactionEntity dbTxOrg2 = buildDbTx("tx-2", "VENDPYMT-002", "VendorPayment", "2024-01-15", "batch-1");

        TransformedTransaction transformedTx1 = buildTransformedTx(
                "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "batch-1", "org-1", List.of());
        TransformedTransaction transformedTx2 = buildTransformedTx(
                "tx-2", "VENDPYMT-002", "VendorPayment", "2024-01-15", "batch-1", "org-2", List.of());

        when(indexerTransactionTransformer.transformForIndexerComparison(any()))
                .thenAnswer(invocation -> {
                    TransactionEntity tx = invocation.getArgument(0);
                    return "tx-1".equals(tx.getId()) ? transformedTx1 : transformedTx2;
                });

        when(indexerService.retrieveTransactionsByDateRange("org-1", dateFrom, dateTo))
                .thenReturn(Either.right(List.of(buildIndexerTx(
                        "tx-1", "VENDPYMT-001", "VendorPayment", "2024-01-15", "org-1", List.of()))));
        when(indexerService.retrieveTransactionsByDateRange("org-2", dateFrom, dateTo))
                .thenReturn(Either.right(List.of(buildIndexerTx(
                        "tx-2", "VENDPYMT-002", "VendorPayment", "2024-01-15", "org-2", List.of()))));

        service.reconcileWithIndexer("org-1", dateFrom, dateTo, Set.of(dbTxOrg1));
        service.reconcileWithIndexer("org-2", dateFrom, dateTo, Set.of(dbTxOrg2));

        verify(indexerService, times(1)).retrieveTransactionsByDateRange("org-1", dateFrom, dateTo);
        verify(indexerService, times(1)).retrieveTransactionsByDateRange("org-2", dateFrom, dateTo);
    }

    @Test
    void shouldCacheFailure_forSameDateRange() {
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 3, 1);
        LocalDate dateTo = LocalDate.of(2024, 3, 31);

        TransactionEntity dbTx = buildDbTx("tx-1", "VENDPYMT-001", "VendorPayment", "2024-03-15", "batch-1");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Indexer down");
        when(indexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo))
                .thenReturn(Either.left(problem));

        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result1 =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));
        Either<ProblemDetail, Map<String, IndexerReconcilationResult>> result2 =
                service.reconcileWithIndexer(organisationId, dateFrom, dateTo, Set.of(dbTx));

        verify(indexerService, times(1)).retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo);
        assertThat(result1.isLeft()).isTrue();
        assertThat(result2.isLeft()).isTrue();
        assertThat(result2.getLeft().getDetail()).isEqualTo("Indexer down");
    }
}
