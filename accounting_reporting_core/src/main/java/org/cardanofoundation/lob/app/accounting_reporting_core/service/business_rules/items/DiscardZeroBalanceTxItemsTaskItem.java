package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus.ERASED_ZERO_BALANCE;

import java.util.Set;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;

public class DiscardZeroBalanceTxItemsTaskItem implements PipelineTaskItem {

    @Override
    public void run(TransactionEntity tx) {
        tx.getItems()
                .stream().filter(txItem -> txItem.getAmountLcy().signum() == 0 && txItem.getAmountFcy().signum() == 0)
                .forEach(txItem -> {
                    txItem.setStatus(ERASED_ZERO_BALANCE);
                    // Removing violations related to this txItem, since it is discarded anyway
                    Set<TransactionViolation> violations = tx.getViolations();
                    violations.removeIf(violation -> violation.getTxItemId().orElse("").equals(txItem.getId()));
                    tx.setViolations(violations);
                });
    }

}
