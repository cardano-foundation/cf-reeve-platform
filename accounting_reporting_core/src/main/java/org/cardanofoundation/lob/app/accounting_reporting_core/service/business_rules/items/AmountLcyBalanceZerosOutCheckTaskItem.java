package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;


import static java.math.BigDecimal.ZERO;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.ERP;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.LCY_BALANCE_MUST_BE_ZERO;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.ERROR;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;

@RequiredArgsConstructor
public class AmountLcyBalanceZerosOutCheckTaskItem implements PipelineTaskItem {

    @Override
    public void run(TransactionEntity tx) {
        Set<TransactionItemEntity> txItems = tx.getItems();

        BigDecimal lcySumDebit = getSumOfOperationType(txItems, OperationType.DEBIT);
        BigDecimal lcySumCredit = getSumOfOperationType(txItems, OperationType.CREDIT);
        BigDecimal lcySum = lcySumDebit.subtract(lcySumCredit);

        if (lcySum.signum() != 0) {
            TransactionViolation v = TransactionViolation.builder()
                    .code(LCY_BALANCE_MUST_BE_ZERO)
                    .severity(ERROR)
                    .source(ERP)
                    .processorModule(this.getClass().getSimpleName())
                    .bag(
                            Map.of(
                                    "transactionNumber", tx.getTransactionInternalNumber()
                            )
                    )
                    .build();

            tx.addViolation(v);
        }
    }

    private static BigDecimal getSumOfOperationType(Set<TransactionItemEntity> txItems, OperationType credit) {
        return txItems.stream()
                .filter(transactionItemEntity -> transactionItemEntity.getOperationType().equals(credit))
                .map(TransactionItemEntity::getAmountLcy)
                .reduce(ZERO, BigDecimal::add);
    }
}
