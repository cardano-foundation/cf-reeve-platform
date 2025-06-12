package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static java.util.stream.Collectors.groupingBy;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus.ERASED_SUM_APPLIED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus.OK;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;

/**
 * Task item that collapses transaction items with the same key by summing their amounts.
 */
@Slf4j
public class TxItemsAmountsSummingTaskItem implements PipelineTaskItem {

    @Override
    public void run(TransactionEntity tx) {
        if (tx.getAutomatedValidationStatus() == FAILED) {
            return;
        }

        // Group items by key
        Map<TransactionItemKey, List<TransactionItemEntity>> itemsPerKeyMap = tx.getItems()
                .stream()
                .collect(groupingBy(txItem -> {
                            return TransactionItemKey.builder()
                                    .costCenterCustomerCode(txItem.getCostCenter().map(CostCenter::getCustomerCode))
                                    .documentVatCustomerCode(txItem.getDocument().flatMap(d -> d.getVat().map(Vat::getCustomerCode)))
                                    .documentNum(txItem.getDocument().map(Document::getNum))
                                    .documentCurrencyId(txItem.getDocument().flatMap(d -> d.getCurrency().getId()))
                                    .documentCounterpartyCustomerCode(txItem.getDocument().flatMap(d -> d.getCounterparty().map(Counterparty::getCustomerCode)))
                                    .accountEventCode(txItem.getAccountEvent().map(AccountEvent::getCode))
                                    .accountCodeDebit(txItem.getAccountDebit().map(Account::getCode))
                                    .accountCodeCredit(txItem.getAccountCredit().map(Account::getCode))
                                    .operationType(Optional.ofNullable(txItem.getOperationType()))
                                    .build();
                        })
                );

        // Mark the original items as ERASED
        tx.getItems().forEach(item -> item.setStatus(ERASED_SUM_APPLIED));

        // Collapsing logic: combine the amounts for items with the same key
        Set<TransactionItemEntity> collapsedItems = itemsPerKeyMap.values().stream()
                .map(items -> items.stream()
                        .reduce((txItem1, txItem2) -> {
                            txItem1.setAmountFcy(txItem1.getAmountFcy().add(txItem2.getAmountFcy()));
                            txItem1.setAmountLcy(txItem1.getAmountLcy().add(txItem2.getAmountLcy()));
                            return txItem1;
                        })
                )
                .filter(Optional::isPresent)
                .map(Optional::get)
                .peek(item -> item.setStatus(OK)) // Mark collapsed items as OK
                .collect(Collectors.toSet());

        // Retain the collapsed valid items in the transaction
        tx.getItems().addAll(collapsedItems); // Add collapsed items back

        // Removing violations of these erased items
        Set<TransactionViolation> violations = tx.getViolations();
        tx.getItems().stream().filter(item -> item.getStatus() == ERASED_SUM_APPLIED)
                .forEach(item -> violations.removeIf(violation -> violation.getTxItemId().orElse("").equals(item.getId())));
        tx.setViolations(violations);
    }

    @EqualsAndHashCode
    @Builder
    @Getter
    public static class TransactionItemKey {

        @Builder.Default
        private Optional<String> costCenterCustomerCode = Optional.empty();

        @Builder.Default
        private Optional<String> documentNum = Optional.empty();

        @Builder.Default
        private Optional<String> documentVatCustomerCode = Optional.empty();

        @Builder.Default
        private Optional<String> documentCounterpartyCustomerCode = Optional.empty();

        @Builder.Default
        private Optional<String> documentCurrencyId = Optional.empty();

        @Builder.Default
        private Optional<String> accountEventCode = Optional.empty();

        @Builder.Default
        private Optional<String> accountCodeDebit = Optional.empty();

        @Builder.Default
        private Optional<String> accountCodeCredit = Optional.empty();

        @Builder.Default
        private Optional<OperationType> operationType = Optional.empty();

    }

}
