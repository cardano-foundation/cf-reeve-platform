package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;

/**
 * Transforms transaction data to match the format published to blockchain/indexer.
 * This ensures that comparisons between DB data and indexer data use the same transformations
 * that were applied when publishing to the blockchain.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IndexerTransactionTransformer {

    private final OrganisationPublicApi organisationPublicApi;

    /**
     * Transforms a transaction entity to match how it would appear in the indexer.
     * Applies the same transformations used when publishing to blockchain:
     * - Cost center parent resolution
     * - FxRevaluation amount handling
     * - Item aggregation (same as TransactionConverter.aggregateTxItems)
     *
     * @param tx The transaction entity to transform
     * @return A transformed view of the transaction for indexer comparison
     */
    public TransformedTransaction transformForIndexerComparison(TransactionEntity tx) {

        String organisationId = tx.getOrganisation().getId();

        // Transform items - mirrors TransactionConverter.convertToDbDetached logic
        Set<TransformedTransactionItem> transformedItems = tx.getItems().stream()
                .map(item -> transformItem(item, tx.getTransactionType(), organisationId))
                .collect(Collectors.toSet());

        // Aggregate items - mirrors TransactionConverter.aggregateTxItems logic
        Set<TransformedTransactionItem> aggregatedItems = aggregateTxItems(transformedItems);

        return TransformedTransaction.builder()
                .id(tx.getId())
                .internalNumber(tx.getInternalTransactionNumber())
                .batchId(tx.getBatchId())
                .transactionType(tx.getTransactionType().name())
                .entryDate(tx.getEntryDate().toString())
                .organisationId(organisationId)
                .items(List.copyOf(aggregatedItems))
                .build();
    }

    /**
     * Transforms a single transaction item - mirrors TransactionConverter.convertToDbDetached(TransactionEntity, TransactionItem)
     */
    private TransformedTransactionItem transformItem(TransactionItemEntity item,
                                                      TransactionType transactionType,
                                                      String organisationId) {
        // Build AccountEvent - mirrors TransactionConverter logic
        String eventCode = item.getAccountEvent().map(e -> e.getCode()).orElse(null);
        String eventName = item.getAccountEvent().map(e -> e.getName()).orElse(null);
        AccountEventHolder accountEvent = (eventCode != null)
                ? new AccountEventHolder(eventCode, eventName)
                : null;

        // Build Project - mirrors TransactionConverter logic
        String projectCustomerCode = item.getProject().map(p -> p.getCustomerCode()).orElse(null);
        String projectName = item.getProject().flatMap(p -> p.getName()).orElse(null);
        ProjectHolder project = (projectCustomerCode != null)
                ? new ProjectHolder(projectCustomerCode, projectName)
                : null;

        // Build CostCenter with parent resolution - mirrors TransactionConverter logic EXACTLY
        CostCenterHolder costCenter = buildCostCenter(item, organisationId);

        // Build Document - mirrors TransactionConverter logic
        DocumentHolder document = buildDocument(item);

        // Handle FxRevaluation: amountFcy should be amountLcy
        BigDecimal amountFcy = item.getAmountFcy();
        if (transactionType == TransactionType.FxRevaluation) {
            amountFcy = item.getAmountLcy();
        }

        BigDecimal fxRate = item.getFxRate();

        return TransformedTransactionItem.builder()
                .id(item.getId())
                .amountFcy(item.getOperationType().equals(OperationType.DEBIT) ? amountFcy : amountFcy.negate())
                .amountLcy(item.getOperationType().equals(OperationType.DEBIT) ? item.getAmountLcy() : item.getAmountLcy().negate())
                .fxRate(fxRate)
                .accountEvent(accountEvent)
                .project(project)
                .costCenter(costCenter)
                .document(document)
                .operationType(item.getOperationType())
                .build();
    }

    /**
     * Builds CostCenter with parent resolution - EXACT copy of TransactionConverter logic
     */
    @Nullable
    private CostCenterHolder buildCostCenter(TransactionItemEntity item, String organisationId) {
        return item.getCostCenter().map(cc -> {
            // If customerCode is empty, return null - mirrors: if(Optional.ofNullable(cc.getCustomerCode()).isEmpty()) return null;
            if (Optional.ofNullable(cc.getCustomerCode()).isEmpty()) {
                return null;
            }

            String customerCode = cc.getCustomerCode();
            String name = cc.getName().orElse(null);

            // Check if cost center has a parent - if so, use parent's code and name
            // This mirrors the EXACT logic from TransactionConverter
            Optional<CostCenter> costCenterOpt = organisationPublicApi.findCostCenter(organisationId, customerCode);
            if (costCenterOpt.isPresent()) {
                CostCenter costCenter = costCenterOpt.get();
                if (costCenter.getParent().isPresent()) {
                    return new CostCenterHolder(
                            costCenter.getParent().get().getId().getCustomerCode(),
                            costCenter.getParent().get().getName()
                    );
                }
            }
            return new CostCenterHolder(customerCode, name);
        }).orElse(null);
    }

    /**
     * Builds Document - mirrors TransactionConverter.convertDocument logic
     */
    @Nullable
    private DocumentHolder buildDocument(TransactionItemEntity item) {
        return item.getDocument().map(doc -> {
            // Currency - mirrors: Currency.builder().id(...).customerCode(...).build()
            CurrencyHolder currency = new CurrencyHolder(
                    doc.getCurrency().getCustomerCode(),
                    doc.getCurrency().getId().orElse(null)
            );

            // Vat - mirrors: doc.getVat().map(vat -> Vat.builder()...).orElse(null)
            VatHolder vat = doc.getVat().map(v -> new VatHolder(
                    v.getCustomerCode(),
                    v.getRate().orElse(null)
            )).orElse(null);

            // Counterparty - mirrors: doc.getCounterparty().map(cp -> Counterparty.builder()...).orElse(null)
            CounterpartyHolder counterparty = doc.getCounterparty().map(cp -> new CounterpartyHolder(
                    cp.getCustomerCode(),
                    cp.getType() != null ? cp.getType().name() : null
            )).orElse(null);

            return new DocumentHolder(doc.getNum(), currency, vat, counterparty);
        }).orElse(null);
    }

    /**
     * Aggregates transaction items by their hash - EXACT copy of TransactionConverter.aggregateTxItems logic
     * This method aggregates transaction items by their aggregatedHash and sums their amounts.
     * IMPORTANT: We sort items by ID before picking the first one to ensure deterministic selection.
     * This is critical because Set iteration order is non-deterministic.
     */
    private Set<TransformedTransactionItem> aggregateTxItems(Set<TransformedTransactionItem> items) {
        return items.stream()
                .collect(Collectors.groupingBy(TransformedTransactionItem::aggregatedHash, Collectors.toSet()))
                .values().stream()
                .map(itemSet -> {
                    // Sort by ID to ensure deterministic selection across runs
                    TransformedTransactionItem aggregatedItem = itemSet.stream()
                            .sorted((a, b) -> a.getId().compareTo(b.getId()))
                            .findFirst()
                            .orElseThrow();
                    BigDecimal aggregatedAmountFcy = itemSet.stream()
                            .map(TransformedTransactionItem::getAmountFcy)
                            .reduce(ZERO, BigDecimal::add);
                    BigDecimal aggregatedAmountLcy = itemSet.stream()
                            .map(TransformedTransactionItem::getAmountLcy)
                            .filter(Objects::nonNull)
                            .reduce(BigDecimal::add)
                            .orElse(null);

                    return aggregatedItem.toBuilder()
                            .amountFcy(aggregatedAmountFcy)
                            .amountLcy(aggregatedAmountLcy)
                            .build();
                })
                .collect(Collectors.toSet());
    }

    // ============== Holder classes that mirror blockchain_publisher entity hashCode behavior ==============

    /**
     * Mirrors blockchain_publisher AccountEvent.hashCode(): Objects.hash(code, name)
     */
    public record AccountEventHolder(String code, String name) {
        @Override
        public int hashCode() {
            return Objects.hash(code, name);
        }
    }

    /**
     * Mirrors blockchain_publisher Project.hashCode(): Objects.hash(customerCode, name)
     */
    public record ProjectHolder(String customerCode, String name) {
        @Override
        public int hashCode() {
            return Objects.hash(customerCode, name);
        }
    }

    /**
     * Mirrors blockchain_publisher CostCenter.hashCode(): Objects.hash(customerCode, name)
     */
    public record CostCenterHolder(String customerCode, String name) {
        @Override
        public int hashCode() {
            return Objects.hash(customerCode, name);
        }
    }

    /**
     * Mirrors blockchain_publisher Currency.hashCode(): Objects.hash(customerCode, id)
     */
    public record CurrencyHolder(String customerCode, String id) {
        @Override
        public int hashCode() {
            return Objects.hash(customerCode, id);
        }
    }

    /**
     * Mirrors blockchain_publisher Vat.hashCode(): Objects.hash(customerCode, rate)
     */
    public record VatHolder(String customerCode, BigDecimal rate) {
        @Override
        public int hashCode() {
            return Objects.hash(customerCode, rate);
        }
    }

    /**
     * Mirrors blockchain_publisher Counterparty.hashCode(): Objects.hash(customerCode, type)
     */
    public record CounterpartyHolder(String customerCode, String type) {
        @Override
        public int hashCode() {
            return Objects.hash(customerCode, type);
        }
    }

    /**
     * Mirrors blockchain_publisher Document.hashCode(): Objects.hash(num, currency, vat, counterparty)
     */
    public record DocumentHolder(String num, CurrencyHolder currency, VatHolder vat, CounterpartyHolder counterparty) {
        @Override
        public int hashCode() {
            return Objects.hash(num, currency, vat, counterparty);
        }
    }

    /**
     * Represents a transformed transaction for indexer comparison.
     */
    @Getter
    @Builder
    public static class TransformedTransaction {
        private final String id;
        private final String internalNumber;
        private final String batchId;
        private final String transactionType;
        private final String entryDate;
        private final String organisationId;
        private final List<TransformedTransactionItem> items;
    }

    /**
     * Represents a transformed transaction item for indexer comparison.
     * Uses holder objects that mirror the exact hashCode behavior of blockchain_publisher entities.
     */
    @Getter
    @Builder(toBuilder = true)
    public static class TransformedTransactionItem {
        private final String id;
        private final BigDecimal amountFcy;
        private final BigDecimal amountLcy;
        private final BigDecimal fxRate;
        private final AccountEventHolder accountEvent;
        private final ProjectHolder project;
        private final CostCenterHolder costCenter;
        private final DocumentHolder document;
        private final OperationType operationType;

        /**
         * Mirrors blockchain_publisher TransactionItemEntity.aggregatedHash():
         * Objects.hash(accountEvent, fxRate, project, costCenter, document)
         */
        public int aggregatedHash() {
            return Objects.hash(accountEvent, fxRate, project, costCenter, document);
        }

        // Convenience getters for comparison with indexer data
        @Nullable
        public String getEventCode() {
            return accountEvent != null ? accountEvent.code() : null;
        }

        @Nullable
        public String getCostCenterCustomerCode() {
            return costCenter != null ? costCenter.customerCode() : null;
        }

        @Nullable
        public String getProjectCustomerCode() {
            return project != null ? project.customerCode() : null;
        }

        @Nullable
        public String getDocumentNumber() {
            return document != null ? document.num() : null;
        }

        @Nullable
        public String getCurrencyCustomerCode() {
            return document != null && document.currency() != null ? document.currency().customerCode() : null;
        }

        @Nullable
        public String getVatCustomerCode() {
            return document != null && document.vat() != null ? document.vat().customerCode() : null;
        }
    }
}
