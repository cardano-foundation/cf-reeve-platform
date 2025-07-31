package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static java.math.BigDecimal.ZERO;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.ERP;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.FCY_BALANCE_MUST_BE_ZERO;
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
public class AmountFcyBalanceZerosOutCheckTaskItem implements PipelineTaskItem {

    @Override
    public void run(TransactionEntity tx) {
        Set<TransactionItemEntity> txItems = tx.getItems();

        BigDecimal fcySumDebit = getSumOfFcy(txItems, OperationType.DEBIT);
        BigDecimal fcySumCredit = getSumOfFcy(txItems, OperationType.CREDIT);
        BigDecimal fcySum = fcySumDebit.subtract(fcySumCredit);

        if (fcySum.signum() != 0) {
            TransactionViolation v = TransactionViolation.builder()
                    .code(FCY_BALANCE_MUST_BE_ZERO)
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

    private static BigDecimal getSumOfFcy(Set<TransactionItemEntity> txItems, OperationType credit) {
        return txItems.stream()
                .filter(transactionItemEntity -> transactionItemEntity.getOperationType().equals(credit))
                .map(TransactionItemEntity::getAmountFcy)
                .reduce(ZERO, BigDecimal::add);
    }


}
