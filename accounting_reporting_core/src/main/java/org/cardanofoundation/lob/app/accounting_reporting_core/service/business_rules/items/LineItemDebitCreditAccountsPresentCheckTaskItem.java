package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.ERP;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.ACCOUNT_CODE_CREDIT_IS_EMPTY;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.ACCOUNT_CODE_DEBIT_IS_EMPTY;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.ERROR;

import java.util.Map;

import lombok.val;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Account;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;

/**
 * Per-line-item balance check: each transaction line item must carry both a debit account code
 * and a credit account code. This replaces relying on the transaction-level total debit/credit
 * amount sum (which required a same-account "dummy" line to zero out) as the source of truth for
 * whether a transaction is balanced.
 */
public class LineItemDebitCreditAccountsPresentCheckTaskItem implements PipelineTaskItem {

    private final boolean enabled;

    public LineItemDebitCreditAccountsPresentCheckTaskItem() {
        this(true);
    }

    public LineItemDebitCreditAccountsPresentCheckTaskItem(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void run(TransactionEntity tx) {
        if (!enabled) return;

        for (val txItem : tx.getItems()) {
            if (isBlank(txItem.getAccountDebit().map(Account::getCode))) {
                val v = TransactionViolation.builder()
                        .code(ACCOUNT_CODE_DEBIT_IS_EMPTY)
                        .txItemId(txItem.getId())
                        .severity(ERROR)
                        .source(ERP)
                        .processorModule(this.getClass().getSimpleName())
                        .bag(Map.of("transactionNumber", tx.getInternalTransactionNumber()))
                        .build();

                tx.addViolation(v);
            }

            if (isBlank(txItem.getAccountCredit().map(Account::getCode))) {
                val v = TransactionViolation.builder()
                        .code(ACCOUNT_CODE_CREDIT_IS_EMPTY)
                        .txItemId(txItem.getId())
                        .severity(ERROR)
                        .source(ERP)
                        .processorModule(this.getClass().getSimpleName())
                        .bag(Map.of("transactionNumber", tx.getInternalTransactionNumber()))
                        .build();

                tx.addViolation(v);
            }
        }
    }

    private static boolean isBlank(java.util.Optional<String> code) {
        return code.map(String::trim).filter(s -> !s.isEmpty()).isEmpty();
    }

}
