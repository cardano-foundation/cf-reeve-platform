package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static java.math.BigDecimal.ZERO;
import static java.util.stream.Collectors.toSet;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.ERP;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.NET_OFF_TX;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.ERROR;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@RequiredArgsConstructor
@Slf4j
public class NetOffCreditDebitTaskItem implements PipelineTaskItem {

    private final OrganisationPublicApiIF organisationPublicApiIF;

    @Override
    public void run(TransactionEntity tx) {

        if (tx.getTransactionType().equals(TransactionType.Journal)) {
            AtomicLong matches = new AtomicLong();
            Optional<String> dummyAccountM = organisationPublicApiIF.findByOrganisationId(tx.getOrganisation().getId())
                    .flatMap(Organisation::getDummyAccount);
            String dummyAccount = dummyAccountM.orElseThrow();

            Set<TransactionItemEntity> itemsDebit = tx.getItems().stream().filter(txItems -> txItems.getAccountDebit().isPresent()
                    && txItems.getAccountDebit().get().getCode().equals(dummyAccount)
            ).collect(toSet());
            Set<TransactionItemEntity> itemsCredit = tx.getItems().stream().filter(txItems -> txItems.getAccountDebit().isPresent()
                    && !txItems.getAccountDebit().get().getCode().equals(dummyAccount)
            ).collect(toSet());
            itemsDebit.forEach(item -> {

                BigDecimal amount = item.getAmountFcy();
                BigDecimal itemToCompare = itemsCredit.stream().filter(txItems -> txItems.getAccountDebit().isPresent()
                                && txItems.getAccountDebit().equals(item.getAccountCredit())
                        ).map(TransactionItemEntity::getAmountFcy)
                        .reduce(ZERO, BigDecimal::add);

                if (Objects.equals(amount, itemToCompare)) {
                    matches.getAndIncrement();
                }
            });

            if (matches.get() == itemsDebit.stream().count()) {
                TransactionViolation v = TransactionViolation.builder()
                        .code(NET_OFF_TX)
                        .severity(ERROR)
                        .source(ERP)
                        .processorModule(this.getClass().getSimpleName())
                        .bag(
                                Map.of(
                                        "transactionNumber", tx.getInternalTransactionNumber()
                                )
                        )
                        .build();

                tx.addViolation(v);
            }
        }

    }

}
