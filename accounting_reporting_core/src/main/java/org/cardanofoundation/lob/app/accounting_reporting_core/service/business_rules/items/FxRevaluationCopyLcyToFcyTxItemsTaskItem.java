package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;



public class FxRevaluationCopyLcyToFcyTxItemsTaskItem implements PipelineTaskItem {

    @Override
    public void run(TransactionEntity tx) {
        tx.getItems()
                .forEach(txItem -> {
                    txItem.setAmountFcy(txItem.getAmountLcy());
                    // Removing violations related to this txItem, since it is discarded anyway
                });
    }

}
