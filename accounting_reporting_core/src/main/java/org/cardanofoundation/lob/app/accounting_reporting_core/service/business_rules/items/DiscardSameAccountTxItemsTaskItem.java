package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus.ERASED_SELF_PAYMENT;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.support.collections.Optionals;

public class DiscardSameAccountTxItemsTaskItem implements PipelineTaskItem {
    @Override
    public void run(TransactionEntity transaction) {
        transaction.getItems()
                .stream().filter(txItem -> Optionals.zip(txItem.getAccountDebit(), txItem.getAccountCredit(), (a, b) -> a.getCode().equals(b.getCode())).orElse(false))
                .forEach(txItem -> txItem.setStatus(ERASED_SELF_PAYMENT));
    }
}
