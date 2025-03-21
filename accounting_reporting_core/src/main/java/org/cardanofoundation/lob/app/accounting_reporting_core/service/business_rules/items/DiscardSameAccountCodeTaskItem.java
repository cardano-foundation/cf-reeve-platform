package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus.ERASED_SELF_PAYMENT;

import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Account;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.support.collections.Optionals;

@Slf4j
public class DiscardSameAccountCodeTaskItem implements PipelineTaskItem {

    @Override
    public void run(TransactionEntity tx) {
        tx.getItems().stream().filter(txItem -> {
            Optional<Account> accountDebit = txItem.getAccountDebit();
            Optional<Account> accountCredit = txItem.getAccountCredit();

            return Optionals.zip(accountDebit, accountCredit, (debit, credit) -> debit.getCode().equals(credit.getCode()))
                    .orElse(false);
        }).forEach(txItem -> txItem.setStatus(ERASED_SELF_PAYMENT));
    }

}
