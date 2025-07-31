package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.ERP;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.ALL_TX_ITEMS_ERASED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.ERROR;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.val;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;

/**
 * Task item that checks if all transaction items are erased, if yes then it fails the transaction.
 */
@RequiredArgsConstructor
public class CheckIfAllTxItemsAreErasedTaskItem implements PipelineTaskItem {

    @Override
    public void run(TransactionEntity tx) {
        if (tx.hasAllItemsErased()) {
            tx.setAutomatedValidationStatus(FAILED);
            handleViolationForEmptyItems(tx);
        }
    }

    private void handleViolationForEmptyItems(TransactionEntity tx) {
        val violation = TransactionViolation.builder()
                .code(ALL_TX_ITEMS_ERASED)
                .severity(ERROR)
                .source(ERP)
                .processorModule(CheckIfAllTxItemsAreErasedTaskItem.class.getSimpleName())
                .bag(createViolationBag(tx))
                .build();

        tx.addViolation(violation);
    }

    private Map<String, Object> createViolationBag(TransactionEntity tx) {
        return Map.of("transactionNumber", tx.getTransactionInternalNumber());
    }

}
