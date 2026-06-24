package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;


import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.ERP;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.AMOUNT_LCY_IS_ZERO;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.ERROR;

import java.util.Map;

import lombok.val;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;

public class AmountsLcyAfterSummingCheckTaskItem implements PipelineTaskItem {

    private final boolean enabled;

    public AmountsLcyAfterSummingCheckTaskItem() {
        this(true);
    }

    public AmountsLcyAfterSummingCheckTaskItem(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void run(TransactionEntity tx) {
        if (!enabled) return;
        for (val txItem : tx.getItems()) {
            if (txItem.getAmountLcy().signum() == 0 && txItem.getAmountFcy().signum() == 0) {
                val v = TransactionViolation.builder()
                        .txItemId(txItem.getId())
                        .code(AMOUNT_LCY_IS_ZERO)
                        .severity(ERROR)
                        .source(ERP)
                        .processorModule(this.getClass().getSimpleName())
                        .bag(
                                Map.of(
                                        "transactionNumber", tx.getInternalTransactionNumber(),
                                        "amountFcy", txItem.getAmountFcy().toEngineeringString()    ,
                                        "amountLcy", txItem.getAmountLcy().toEngineeringString()
                                )
                        )
                        .build();

                tx.addViolation(v);
            }
        }
    }

}
