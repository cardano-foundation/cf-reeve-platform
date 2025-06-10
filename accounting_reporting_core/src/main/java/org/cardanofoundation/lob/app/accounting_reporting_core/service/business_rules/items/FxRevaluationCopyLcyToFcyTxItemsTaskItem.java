package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.FxRevaluation;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;


public class FxRevaluationCopyLcyToFcyTxItemsTaskItem implements PipelineTaskItem {

    @Override
    public void run(TransactionEntity tx) {
        if (tx.getTransactionType() != FxRevaluation) {
            return;
        }
        tx.getItems()
                .forEach(txItem -> {
                    txItem.setAmountFcy(txItem.getAmountLcy());
                });
    }

}
