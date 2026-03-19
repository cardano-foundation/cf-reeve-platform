package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OnChainTransactionDto;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OnChainTransactionItemDto;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.IndexerTransactionTransformer.TransformedTransaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.IndexerTransactionTransformer.TransformedTransactionItem;
import org.cardanofoundation.lob.app.support.collections.Partitions;

@Slf4j
@RequiredArgsConstructor
public class OnChainIndexerReconcilationService implements IndexerReconcilationServiceIF {

    private final OnChainIndexerService onChainIndexerService;
    private final IndexerTransactionTransformer indexerTransactionTransformer;
    private final ObjectMapper objectMapper;

    private String cachedIndexerKey;
    private Either<ProblemDetail, List<OnChainTransactionDto>> cachedIndexerTransactions;

    /**
     * Structured diff for a single field mismatch between DB and indexer.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldMismatch(String field, String reeve, String indexer) {}

    /**
     * Item count mismatch between DB and indexer for a transaction.
     */
    public record ItemCountMismatch(int reeve, int indexer) {}

    /**
     * Structured diff for a single transaction item. Fields are omitted when null.
     * - notFound=true + dbItem + indexerItems: item exists in DB but has no matching indexer item
     * - fieldMismatches: item matched by content key but has field-level differences
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemDiff(
            String itemId,
            Boolean notFound,
            TransformedTransactionItem dbItem,
            List<OnChainTransactionItemDto> indexerItems,
            List<FieldMismatch> fieldMismatches
    ) {}

    /**
     * Top-level reconciliation diff for a transaction. Fields are omitted when null.
     * - txNotInDb=true: transaction exists in indexer but not in DB
     * - txFieldMismatches: transaction-level field differences (internalNumber, type, date)
     * - itemCountMismatch: DB and indexer have different item counts
     * - itemDiffs: per-item differences (not-found or field mismatches)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReconciliationDiff(
            Boolean txNotInDb,
            List<FieldMismatch> txFieldMismatches,
            ItemCountMismatch itemCountMismatch,
            List<ItemDiff> itemDiffs
    ) {}

    /**
     * Reconciles transactions from the database with transactions from the On-Chain Indexer.
     *
     * @param organisationId The organisation ID to reconcile
     * @param dateFrom       Start date for reconciliation
     * @param dateTo         End date for reconciliation
     * @param dbTransactions Transactions from the database to compare
     * @return Either a Problem if the API call fails, or a Map of transaction IDs to their reconciliation status
     */
    @Override
    public Either<ProblemDetail, Map<String, IndexerReconcilationResult>> reconcileWithIndexer(
            String organisationId,
            LocalDate dateFrom,
            LocalDate dateTo,
            Set<TransactionEntity> dbTransactions) {

        log.info("Starting indexer reconciliation for organisation: {}, from: {}, to: {}, db transactions count: {}",
                organisationId, dateFrom, dateTo, dbTransactions.size());

        String cacheKey = organisationId + "|" + dateFrom + "|" + dateTo;
        if (cachedIndexerTransactions == null || !cacheKey.equals(cachedIndexerKey)) {
            log.info("Fetching transactions from indexer for key: {}", cacheKey);
            cachedIndexerTransactions = onChainIndexerService.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo);
            cachedIndexerKey = cacheKey;
        } else {
            log.info("Using cached indexer transactions for key: {}", cacheKey);
        }

        if (cachedIndexerTransactions.isLeft()) {
            log.error("Failed to retrieve transactions from indexer: {}", cachedIndexerTransactions.getLeft().getDetail());
            return Either.left(cachedIndexerTransactions.getLeft());
        }

        List<OnChainTransactionDto> indexerTransactions = cachedIndexerTransactions.get();
        log.info("Retrieved {} transactions from indexer", indexerTransactions.size());

        // Pre-compute indexer map once to avoid recreating it for each partition
        Map<String, OnChainTransactionDto> indexerTxMap = indexerTransactions.stream()
                .collect(Collectors.toMap(OnChainTransactionDto::id, Function.identity()));

        Map<String, IndexerReconcilationResult> results = new LinkedHashMap<>();
        Partitions.partition(dbTransactions, 100).forEach(dbTransactionsPartition -> {
            Map<String, IndexerReconcilationResult> resultPart = compareTransactionsPartition(dbTransactionsPartition.asSet(), indexerTxMap);
            results.putAll(resultPart);
            long okCount = resultPart.values().stream().filter(r -> r.status() == ReconcilationCode.OK).count();
            long nokCount = resultPart.values().stream().filter(r -> r.status() == ReconcilationCode.NOK).count();
            log.info("Processed {} partition of {}\ncompleted. OK: {}, NOK: {}", dbTransactionsPartition.getPartitionIndex(), dbTransactionsPartition.getTotalPartitions(), okCount, nokCount);
        });

        // Check for orphaned indexer transactions once with all DB transactions
        checkForOrphanedIndexerTransactions(dbTransactions, indexerTransactions, results);

        long okCount = results.values().stream().filter(r -> r.status() == ReconcilationCode.OK).count();
        long nokCount = results.values().stream().filter(r -> r.status() == ReconcilationCode.NOK).count();

        log.info("Indexer reconciliation completed. OK: {}, NOK: {}", okCount, nokCount);

        return Either.right(results);
    }

    private Map<String, IndexerReconcilationResult> compareTransactionsPartition(
            Set<TransactionEntity> dbTransactions,
            Map<String, OnChainTransactionDto> indexerTxMap) {

        Map<String, IndexerReconcilationResult> results = new LinkedHashMap<>();

        for (TransactionEntity dbTx : dbTransactions) {
            String txId = dbTx.getId();
            OnChainTransactionDto indexerTx = indexerTxMap.get(txId);

            if (indexerTx == null) {
                continue;
            }

            TransformedTransaction transformedDbTx = indexerTransactionTransformer.transformForIndexerComparison(dbTx);
            Optional<ReconciliationDiff> diff = compareTransaction(transformedDbTx, indexerTx);

            if (diff.isPresent()) {
                results.put(txId, new IndexerReconcilationResult(
                        ReconcilationCode.NOK,
                        serializeDiff(diff.get())
                ));
            } else {
                results.put(txId, new IndexerReconcilationResult(
                        ReconcilationCode.OK,
                        null
                ));
            }
        }

        return results;
    }

    private void checkForOrphanedIndexerTransactions(
            Set<TransactionEntity> dbTransactions,
            List<OnChainTransactionDto> indexerTransactions,
            Map<String, IndexerReconcilationResult> results) {

        Set<String> dbTxIds = dbTransactions.stream()
                .map(TransactionEntity::getId)
                .collect(Collectors.toSet());

        for (OnChainTransactionDto indexerTx : indexerTransactions) {
            if (!dbTxIds.contains(indexerTx.id())) {
                log.warn("Transaction {} ({}) found in indexer but not in database", indexerTx.internalNumber(), indexerTx.id());
                results.put(indexerTx.id(), new IndexerReconcilationResult(
                        ReconcilationCode.NOK,
                        serializeDiff(new ReconciliationDiff(true, null, null, null))
                ));
            }
        }
    }

    private Optional<ReconciliationDiff> compareTransaction(TransformedTransaction transformedDbTx, OnChainTransactionDto indexerTx) {
        List<FieldMismatch> txFieldMismatches = new ArrayList<>();

        collectMismatch(txFieldMismatches, "internalNumber",
                transformedDbTx.getInternalNumber(), indexerTx.internalNumber());
        collectMismatch(txFieldMismatches, "type",
                transformedDbTx.getTransactionType(), indexerTx.type());
        collectMismatch(txFieldMismatches, "date",
                transformedDbTx.getEntryDate(), indexerTx.date());

        List<TransformedTransactionItem> dbItems = transformedDbTx.getItems();
        List<OnChainTransactionItemDto> indexerItems = indexerTx.items();

        ItemCountMismatch itemCountMismatch = null;
        List<ItemDiff> itemDiffs = List.of();

        if (dbItems.size() != indexerItems.size()) {
            itemCountMismatch = new ItemCountMismatch(dbItems.size(), indexerItems.size());
        } else {
            itemDiffs = compareItems(dbItems, indexerItems);
        }

        if (txFieldMismatches.isEmpty() && itemCountMismatch == null && itemDiffs.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new ReconciliationDiff(
                null,
                txFieldMismatches.isEmpty() ? null : txFieldMismatches,
                itemCountMismatch,
                itemDiffs.isEmpty() ? null : itemDiffs
        ));
    }

    private List<ItemDiff> compareItems(
            List<TransformedTransactionItem> dbItems,
            List<OnChainTransactionItemDto> indexerItems) {

        List<ItemDiff> itemDiffs = new ArrayList<>();

        // Match items by content key instead of ID, because the aggregation process
        // uses iterator().next() which is non-deterministic for Sets.
        // This means different item IDs can survive aggregation between publisher and reconciler.
        Map<String, OnChainTransactionItemDto> indexerItemsMap = indexerItems.stream()
                .collect(Collectors.toMap(this::computeIndexerItemContentKey, Function.identity(),
                        (existing, replacement) -> existing)); // Keep first in case of duplicates

        // Pre-compute which indexer items are not matched by any DB item.
        // These are the candidates shown when a DB item has no counterpart in the indexer.
        Set<String> matchedIndexerKeys = dbItems.stream()
                .map(this::computeDbItemContentKey)
                .filter(indexerItemsMap::containsKey)
                .collect(Collectors.toSet());
        List<OnChainTransactionItemDto> unmatchedIndexerItems = indexerItems.stream()
                .filter(item -> !matchedIndexerKeys.contains(computeIndexerItemContentKey(item)))
                .toList();

        for (TransformedTransactionItem dbItem : dbItems) {
            String contentKey = computeDbItemContentKey(dbItem);
            OnChainTransactionItemDto indexerItem = indexerItemsMap.get(contentKey);

            if (indexerItem == null) {
                itemDiffs.add(new ItemDiff(dbItem.getId(), true, dbItem, unmatchedIndexerItems, null));
            } else {
                List<FieldMismatch> fieldMismatches = compareItemFields(dbItem, indexerItem);
                if (!fieldMismatches.isEmpty()) {
                    itemDiffs.add(new ItemDiff(dbItem.getId(), null, null, null, fieldMismatches));
                }
            }
        }

        return itemDiffs;
    }

    /**
     * Computes a content-based key for a DB item to match with indexer items.
     * Uses the same fields that define item identity (from aggregatedHash) plus fxRate.
     * Note: Currency is excluded because DB has customerCode ("CHF") while indexer has full ID ("ISO_4217:CHF")
     */
    private String computeDbItemContentKey(TransformedTransactionItem item) {
        return String.join("|",
                nullSafe(item.getEventCode()),
                normalizeFxRate(item.getFxRate()),
                nullSafe(item.getProjectCustomerCode()),
                nullSafe(item.getCostCenterCustomerCode()),
                nullSafe(item.getDocumentNumber()),
                nullSafe(item.getVatCustomerCode())
        );
    }

    /**
     * Computes a content-based key for an indexer item to match with DB items.
     */
    private String computeIndexerItemContentKey(OnChainTransactionItemDto item) {
        return String.join("|",
                nullSafe(item.eventCode()),
                normalizeFxRate(item.fxRate()),
                nullSafe(item.projectCustCode()),
                nullSafe(item.costCenterCustCode()),
                nullSafe(item.documentNumber()),
                nullSafe(item.vatCustCode())
        );
    }

    private List<FieldMismatch> compareItemFields(TransformedTransactionItem dbItem, OnChainTransactionItemDto indexerItem) {
        List<FieldMismatch> mismatches = new ArrayList<>();

        // amountFcy is NOT in the content key used for matching, so it can differ
        if (dbItem.getAmountFcy().compareTo(indexerItem.amountFcy()) != 0) {
            mismatches.add(new FieldMismatch("amountFcy",
                    dbItem.getAmountFcy().toPlainString(),
                    indexerItem.amountFcy().toPlainString()));
        }

        // currency is NOT in the content key (excluded due to DB customerCode vs indexer full ID format)
        String dbCurrencyId = dbItem.getDocument() != null && dbItem.getDocument().currency() != null
                ? dbItem.getDocument().currency().id() : null;
        collectMismatch(mismatches, "currency", dbCurrencyId, indexerItem.currency());

        // Note: fxRate, documentNumber, costCenterCustCode, projectCustCode, eventCode, vatCustCode
        // are all part of the content key used to match items — if we reach here they are already equal.

        return mismatches;
    }

    /**
     * Normalizes fxRate to handle different representations (BigDecimal vs String, different precision)
     */
    private String normalizeFxRate(Object fxRate) {
        if (fxRate == null) {
            return "";
        }
        try {
            BigDecimal rate = fxRate instanceof BigDecimal
                    ? (BigDecimal) fxRate
                    : new BigDecimal(fxRate.toString());
            return rate.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return fxRate.toString();
        }
    }

    private void collectMismatch(List<FieldMismatch> mismatches, String field, String reeve, String indexer) {
        if (!Objects.equals(reeve, indexer)) {
            mismatches.add(new FieldMismatch(field, reeve, indexer));
        }
    }

    private String nullSafe(Object value) {
        return value == null ? "" : value.toString();
    }

    private String serializeDiff(ReconciliationDiff diff) {
        try {
            return objectMapper.writeValueAsString(diff);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize reconciliation diff", e);
            return "{\"error\":\"Failed to serialize diff\"}";
        }
    }
}
