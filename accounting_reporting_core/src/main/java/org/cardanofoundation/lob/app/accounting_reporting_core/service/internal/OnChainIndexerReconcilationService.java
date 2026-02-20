package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

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

    private static final String MISMATCH_SEPARATOR = "; ";
    private static final String INDEXER_LABEL = ", Indexer=";
    private static final String DB_LABEL = "DB=";
    private static final String ITEM_PREFIX = "Item ";

    private final OnChainIndexerService onChainIndexerService;
    private final IndexerTransactionTransformer indexerTransactionTransformer;

    private String cachedIndexerKey;
    private Either<Problem, List<OnChainTransactionDto>> cachedIndexerTransactions;

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
    public Either<Problem, Map<String, IndexerReconcilationResult>> reconcileWithIndexer(
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
                //log.warn("Transaction {} ({}) not found in indexer", dbTx.getInternalTransactionNumber(), txId);
                results.put(txId, new IndexerReconcilationResult(
                        ReconcilationCode.NOK,
                        "Transaction not found in indexer"
                ));
                continue;
            }

            TransformedTransaction transformedDbTx = indexerTransactionTransformer.transformForIndexerComparison(dbTx);
            Optional<String> mismatchReason = compareTransaction(transformedDbTx, indexerTx);

            if (mismatchReason.isPresent()) {
                //log.warn("Transaction {} ({}) has mismatch: {}", dbTx.getInternalTransactionNumber(), txId, mismatchReason.get());
                results.put(txId, new IndexerReconcilationResult(
                        ReconcilationCode.NOK,
                        mismatchReason.get()
                ));
            } else {
                //log.info("Transaction {} ({}) is OK",dbTx.getInternalTransactionNumber(), txId);
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
                        "Transaction found in indexer but not in database"
                ));
            }
        }
    }

    private Optional<String> compareTransaction(TransformedTransaction transformedDbTx, OnChainTransactionDto indexerTx) {
        StringBuilder mismatches = new StringBuilder();

        appendMismatchIfNotEqual(mismatches, "Internal number",
                transformedDbTx.getInternalNumber(), indexerTx.internalNumber());

        appendMismatchIfNotEqual(mismatches, "Type",
                transformedDbTx.getTransactionType(), indexerTx.type());

        appendMismatchIfNotEqual(mismatches, "Date",
                transformedDbTx.getEntryDate(), indexerTx.date());

        //appendMismatchIfNotEqual(mismatches, "Batch ID",
        //        transformedDbTx.getBatchId(), indexerTx.batchId());

        compareItemsCounts(mismatches, transformedDbTx, indexerTx);

        return mismatches.isEmpty() ? Optional.empty() : Optional.of(mismatches.toString());
    }

    private void compareItemsCounts(StringBuilder mismatches,
                                    TransformedTransaction transformedDbTx,
                                    OnChainTransactionDto indexerTx) {
        List<TransformedTransactionItem> dbItems = transformedDbTx.getItems();
        List<OnChainTransactionItemDto> indexerItems = indexerTx.items();

        if (dbItems.size() != indexerItems.size()) {
            mismatches.append("Items count mismatch: ")
                    .append(DB_LABEL).append(dbItems.size())
                    .append(INDEXER_LABEL).append(indexerItems.size())
                    .append(MISMATCH_SEPARATOR);
        } else {
            compareItems(dbItems, indexerItems).ifPresent(mismatches::append);
        }
    }

    private void appendMismatchIfNotEqual(StringBuilder mismatches, String fieldName, String dbValue, String indexerValue) {
        if (!dbValue.equals(indexerValue)) {
            mismatches.append(fieldName).append(" mismatch: ")
                    .append(DB_LABEL).append(dbValue)
                    .append(INDEXER_LABEL).append(indexerValue)
                    .append(MISMATCH_SEPARATOR);
        }
    }

    private Optional<String> compareItems(
            List<TransformedTransactionItem> dbItems,
            List<OnChainTransactionItemDto> indexerItems) {

        StringBuilder mismatches = new StringBuilder();

        // Match items by content key instead of ID, because the aggregation process
        // uses iterator().next() which is non-deterministic for Sets.
        // This means different item IDs can survive aggregation between publisher and reconciler.
        Map<String, OnChainTransactionItemDto> indexerItemsMap = indexerItems.stream()
                .collect(Collectors.toMap(this::computeIndexerItemContentKey, Function.identity(),
                        (existing, replacement) -> existing)); // Keep first in case of duplicates

        for (TransformedTransactionItem dbItem : dbItems) {
            String contentKey = computeDbItemContentKey(dbItem);
            OnChainTransactionItemDto indexerItem = indexerItemsMap.get(contentKey);

            if (indexerItem == null) {
                mismatches.append(ITEM_PREFIX)
                        .append(dbItem.getId())
                        .append(" not found in indexer")
                        .append(MISMATCH_SEPARATOR);
                continue;
            }

            compareItemFields(mismatches, dbItem, indexerItem);
        }

        return mismatches.isEmpty() ? Optional.empty() : Optional.of(mismatches.toString());
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

    /**
     * Normalizes fxRate to handle different representations (BigDecimal vs String, different precision)
     */
    private String normalizeFxRate(Object fxRate) {
        if (fxRate == null) {
            return "";
        }
        try {
            // Convert to BigDecimal and use stripTrailingZeros for consistent comparison
            BigDecimal rate = fxRate instanceof BigDecimal
                    ? (BigDecimal) fxRate
                    : new BigDecimal(fxRate.toString());
            return rate.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return fxRate.toString();
        }
    }

    private String nullSafe(Object value) {
        return value == null ? "" : value.toString();
    }

    private void compareItemFields(StringBuilder mismatches,
                                   TransformedTransactionItem dbItem,
                                   OnChainTransactionItemDto indexerItem) {
        String itemId = dbItem.getId();

        compareAmountFcy(mismatches, itemId, dbItem, indexerItem);
        compareFxRate(mismatches, itemId, dbItem, indexerItem);
        appendItemMismatchIfNotEqual(mismatches, itemId, "documentNumber",
                dbItem.getDocumentNumber(), indexerItem.documentNumber());
        appendItemMismatchIfNotEqual(mismatches, itemId, "costCenterCustCode",
                dbItem.getCostCenterCustomerCode(), indexerItem.costCenterCustCode());
        appendItemMismatchIfNotEqual(mismatches, itemId, "projectCustCode",
                dbItem.getProjectCustomerCode(), indexerItem.projectCustCode());
        appendItemMismatchIfNotEqual(mismatches, itemId, "eventCode",
                dbItem.getEventCode(), indexerItem.eventCode());
        appendItemMismatchIfNotEqual(mismatches, itemId, "vatCustCode",
                dbItem.getVatCustomerCode(), indexerItem.vatCustCode());
    }

    private void compareAmountFcy(StringBuilder mismatches, String itemId,
                                  TransformedTransactionItem dbItem,
                                  OnChainTransactionItemDto indexerItem) {
        if (dbItem.getAmountFcy() != null && dbItem.getAmountFcy().compareTo(indexerItem.amountFcy()) != 0) {
            appendItemMismatch(mismatches, itemId, "amountFcy", dbItem.getAmountFcy(), indexerItem.amountFcy());
        }
    }

    private void compareFxRate(StringBuilder mismatches, String itemId,
                               TransformedTransactionItem dbItem,
                               OnChainTransactionItemDto indexerItem) {
        if (dbItem.getFxRate() != null) {
            BigDecimal indexerFxRate = new BigDecimal(indexerItem.fxRate());
            if (dbItem.getFxRate().compareTo(indexerFxRate) != 0) {
                appendItemMismatch(mismatches, itemId, "fxRate", dbItem.getFxRate(), indexerFxRate);
            }
        }
    }

    private void appendItemMismatchIfNotEqual(StringBuilder mismatches, String itemId,
                                              String fieldName, String dbValue, String indexerValue) {
        if (dbValue != null && !dbValue.equals(indexerValue)) {
            appendItemMismatch(mismatches, itemId, fieldName, dbValue, indexerValue);
        }
    }

    private void appendItemMismatch(StringBuilder mismatches, String itemId,
                                    String fieldName, Object dbValue, Object indexerValue) {
        mismatches.append(ITEM_PREFIX)
                .append(itemId)
                .append(" ").append(fieldName).append(" mismatch: ")
                .append(DB_LABEL).append(dbValue)
                .append(INDEXER_LABEL).append(indexerValue)
                .append(MISMATCH_SEPARATOR);
    }
}
