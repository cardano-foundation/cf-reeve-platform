package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.TRANSACTION_TYPE_UNKNOWN;

import java.util.Map;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;

public class TransactionTypeUnknownTaskItem implements PipelineTaskItem {

    private final boolean enabled;

    public TransactionTypeUnknownTaskItem() {
        this(true);
    }

    public TransactionTypeUnknownTaskItem(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void run(TransactionEntity transaction) {
        if (!enabled) return;
        if (transaction.getTransactionType() == null) {
            transaction.setTransactionType(TransactionType.Unknown);
        }

        if(transaction.getTransactionType() == TransactionType.Unknown) {
            TransactionViolation unkownViolation = TransactionViolation.builder()
                    .code(TRANSACTION_TYPE_UNKNOWN)
                    .severity(Violation.Severity.ERROR)
                    .source(Source.ERP)
                    .processorModule(this.getClass().getSimpleName())
                    .bag(Map.of("transactionNumber", transaction.getInternalTransactionNumber()))
                    .build();
            transaction.addViolation(unkownViolation);
        }
    }
}
