package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

/**
 * Task item that collapses transaction items with the same key by summing their amounts.
 */
@Slf4j
public class TxTotalAmountSummTaskItem implements PipelineTaskItem {

    private final OrganisationPublicApi organisationPublicApiIF;

    public TxTotalAmountSummTaskItem(OrganisationPublicApi organisationPublicApiIF) {
        this.organisationPublicApiIF = organisationPublicApiIF;
    }

    @Override
    public void run(TransactionEntity tx) {
        if (tx.getAutomatedValidationStatus() == FAILED) {
            return;
        }
        tx.setTotalAmountLcy(getAmountLcyTotalForAllDebitItems(tx));
    }

    public BigDecimal getAmountLcyTotalForAllDebitItems(TransactionEntity tx) {
        Set<TransactionItemEntity> items = tx.getItems();

        if (tx.getTransactionType().equals(TransactionType.Journal)) {
            Optional<String> dummyAccount = organisationPublicApiIF.findByOrganisationId(tx.getOrganisation().getId()).orElse(new org.cardanofoundation.lob.app.organisation.domain.entity.Organisation()).getDummyAccount();
            items = tx.getItems().stream().filter(txItems -> txItems.getAccountDebit().isPresent() && txItems.getAccountDebit().get().getCode().equals(dummyAccount.orElse(""))).collect(Collectors.toSet());
        }

        if (tx.getTransactionType().equals(TransactionType.FxRevaluation)) {
            BigDecimal totalCredit = items.stream()
                    .filter(item -> item.getOperationType().equals(OperationType.CREDIT))
                    .map(TransactionItemEntity::getAmountLcy)
                    .reduce(BigDecimal.ZERO, BigDecimal::add); // Use ZERO as identity for sum

            BigDecimal totalDebit = items.stream()
                    .filter(item -> item.getOperationType().equals(OperationType.DEBIT))
                    .map(TransactionItemEntity::getAmountLcy)
                    .reduce(BigDecimal.ZERO, BigDecimal::add); // Use ZERO as identity for sum

            return totalCredit.subtract(totalDebit).abs();
        }

        return items.stream()
                .map(TransactionItemEntity::getAmountLcy)
                .reduce(BigDecimal.ZERO, BigDecimal::add).abs();
    }

}
