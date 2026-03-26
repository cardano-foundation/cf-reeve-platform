package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.support.calc.BigDecimals;

/**
 * Produces a structured, human-readable diff between a LOB (attached) transaction and an ERP
 * (detached) transaction, covering only the fields tracked by
 * {@link ERPSourceTransactionVersionCalculator}. Replaces the verbose JaVers JSON blob that was
 * previously stored in {@code ReconcilationViolation.sourceDiff}.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ERPSourceReconciliationDiffCalculator {

    private final ObjectMapper objectMapper;

    /** A single field-level mismatch between the LOB DB value and the ERP value. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldMismatch(String field, String reeve, String erp) {}

    /** Number of items differs between LOB and ERP. */
    public record ItemCountMismatch(int reeve, int erp) {}

    /**
     * Diff for a single transaction item.
     * - {@code onlyInLob=true}: item exists in LOB but not in ERP (by item ID)
     * - {@code onlyInErp=true}: item exists in ERP but not in LOB (by item ID)
     * - {@code fieldMismatches}: item matched by ID but has field-level differences
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemDiff(
            String itemId,
            Boolean onlyInLob,
            Boolean onlyInErp,
            List<FieldMismatch> fieldMismatches
    ) {}

    /**
     * Top-level ERP reconciliation diff for a transaction.
     * - {@code txFieldMismatches}: transaction-level field differences
     * - {@code itemCountMismatch}: LOB and ERP have different item counts
     * - {@code itemDiffs}: per-item differences
     * - {@code rollbackTransaction}: item-level ID matching is skipped for rollback transactions
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ERPReconciliationDiff(
            List<FieldMismatch> txFieldMismatches,
            ItemCountMismatch itemCountMismatch,
            List<ItemDiff> itemDiffs,
            Boolean rollbackTransaction
    ) {}

    /**
     * Computes a structured diff between {@code lobTx} (LOB DB) and {@code erpTx} (ERP source)
     * and serializes it as a JSON string.
     */
    public String computeDiff(TransactionEntity lobTx, TransactionEntity erpTx) {
        List<FieldMismatch> txMismatches = new ArrayList<>();

        compareField(txMismatches, "internalTransactionNumber",
                lobTx.getInternalTransactionNumber(), erpTx.getInternalTransactionNumber());
        compareField(txMismatches, "transactionType",
                str(lobTx.getTransactionType()), str(erpTx.getTransactionType()));
        compareField(txMismatches, "entryDate",
                str(lobTx.getEntryDate()), str(erpTx.getEntryDate()));
        compareField(txMismatches, "organisation.id",
                lobTx.getOrganisation().getId(), erpTx.getOrganisation().getId());

        int lobItemCount = lobTx.getItems().size();
        int erpItemCount = erpTx.getItems().size();
        ItemCountMismatch countMismatch = lobItemCount != erpItemCount
                ? new ItemCountMismatch(lobItemCount, erpItemCount)
                : null;

        // For rollback transactions, item IDs differ between LOB and ERP — skip ID-based matching
        boolean isRollback = lobTx.getRollbackSuffix() != null;
        List<ItemDiff> itemDiffs = isRollback ? null : computeItemDiffs(lobTx, erpTx);

        ERPReconciliationDiff diff = new ERPReconciliationDiff(
                txMismatches.isEmpty() ? null : txMismatches,
                countMismatch,
                itemDiffs != null && itemDiffs.isEmpty() ? null : itemDiffs,
                isRollback ? true : null
        );

        try {
            return objectMapper.writeValueAsString(diff);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ERP reconciliation diff for tx {}", lobTx.getId(), e);
            return "{\"error\":\"Failed to serialize diff\"}";
        }
    }

    private List<ItemDiff> computeItemDiffs(TransactionEntity lobTx, TransactionEntity erpTx) {
        Map<String, TransactionItemEntity> lobItems = lobTx.getItems().stream()
                .collect(LinkedHashMap::new,
                        (m, i) -> m.put(i.getId(), i),
                        Map::putAll);
        Map<String, TransactionItemEntity> erpItems = erpTx.getItems().stream()
                .collect(LinkedHashMap::new,
                        (m, i) -> m.put(i.getId(), i),
                        Map::putAll);

        List<ItemDiff> itemDiffs = new ArrayList<>();

        for (String itemId : lobItems.keySet()) {
            if (!erpItems.containsKey(itemId)) {
                itemDiffs.add(new ItemDiff(itemId, true, null, null));
            }
        }

        for (String itemId : erpItems.keySet()) {
            if (!lobItems.containsKey(itemId)) {
                itemDiffs.add(new ItemDiff(itemId, null, true, null));
            }
        }

        for (Map.Entry<String, TransactionItemEntity> entry : lobItems.entrySet()) {
            String itemId = entry.getKey();
            if (!erpItems.containsKey(itemId)) continue;
            List<FieldMismatch> mismatches = compareItem(entry.getValue(), erpItems.get(itemId));
            if (!mismatches.isEmpty()) {
                itemDiffs.add(new ItemDiff(itemId, null, null, mismatches));
            }
        }

        return itemDiffs;
    }

    private List<FieldMismatch> compareItem(TransactionItemEntity lob, TransactionItemEntity erp) {
        List<FieldMismatch> mismatches = new ArrayList<>();

        compareField(mismatches, "accountCredit.code",
                lob.getAccountCredit().map(Account::getCode).orElse(null),
                erp.getAccountCredit().map(Account::getCode).orElse(null));
        compareField(mismatches, "accountDebit.code",
                lob.getAccountDebit().map(Account::getCode).orElse(null),
                erp.getAccountDebit().map(Account::getCode).orElse(null));
        compareField(mismatches, "fxRate",
                normaliseDecimal(lob.getFxRate()), normaliseDecimal(erp.getFxRate()));
        compareField(mismatches, "amountFcy",
                normaliseDecimal(lob.getAmountFcy()), normaliseDecimal(erp.getAmountFcy()));
        compareField(mismatches, "amountLcy",
                normaliseDecimal(lob.getAmountLcy()), normaliseDecimal(erp.getAmountLcy()));
        compareField(mismatches, "costCenter.customerCode",
                lob.getCostCenter().map(CostCenter::getCustomerCode).orElse(null),
                erp.getCostCenter().map(CostCenter::getCustomerCode).orElse(null));
        compareField(mismatches, "project.customerCode",
                lob.getProject().map(Project::getCustomerCode).orElse(null),
                erp.getProject().map(Project::getCustomerCode).orElse(null));

        boolean lobHasDoc = lob.getDocument().isPresent();
        boolean erpHasDoc = erp.getDocument().isPresent();
        if (lobHasDoc && erpHasDoc) {
            compareDocumentFields(mismatches, lob.getDocument().get(), erp.getDocument().get());
        } else if (lobHasDoc) {
            mismatches.add(new FieldMismatch("document", "present", "absent"));
        } else if (erpHasDoc) {
            mismatches.add(new FieldMismatch("document", "absent", "present"));
        }

        return mismatches;
    }

    private void compareDocumentFields(List<FieldMismatch> mismatches, Document lob, Document erp) {
        compareField(mismatches, "document.num", lob.getNum(), erp.getNum());
        compareField(mismatches, "document.currency.customerCode",
                lob.getCurrency().getCustomerCode(), erp.getCurrency().getCustomerCode());
        compareField(mismatches, "document.counterparty.customerCode",
                lob.getCounterparty().map(Counterparty::getCustomerCode).orElse(null),
                erp.getCounterparty().map(Counterparty::getCustomerCode).orElse(null));
        compareField(mismatches, "document.vat.customerCode",
                lob.getVat().map(Vat::getCustomerCode).orElse(null),
                erp.getVat().map(Vat::getCustomerCode).orElse(null));
    }

    private void compareField(List<FieldMismatch> mismatches, String field, String reeve, String erp) {
        if (!Objects.equals(reeve, erp)) {
            mismatches.add(new FieldMismatch(field, reeve, erp));
        }
    }

    private String normaliseDecimal(BigDecimal value) {
        return value == null ? null : BigDecimals.normaliseString(value);
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

}
