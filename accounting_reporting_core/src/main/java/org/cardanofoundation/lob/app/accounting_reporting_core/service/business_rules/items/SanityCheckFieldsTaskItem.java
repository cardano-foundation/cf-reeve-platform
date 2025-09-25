package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.ERP;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.LOB;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.DOCUMENT_NAME_MUST_BE_SET;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.ENTRY_DATE_MUST_BE_PRESENT;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.FX_RATE_MUST_BE_GREATER_THAN_ZERO;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.TX_INTERNAL_NUMBER_MUST_BE_PRESENT;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.TX_VALIDATION_ERROR;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.ERROR;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;

@RequiredArgsConstructor
public class SanityCheckFieldsTaskItem implements PipelineTaskItem {

    private final Validator validator;

    @Override
    public void run(TransactionEntity tx) {
        Set<ConstraintViolation<TransactionEntity>> errors = validator.validate(tx);

        if (tx.getAutomatedValidationStatus() == FAILED) {
            return;
        }

        if (!errors.isEmpty()) {
            TransactionViolation v = TransactionViolation.builder()
                    .code(TX_VALIDATION_ERROR)
                    .severity(ERROR)
                    .source(LOB)
                    .processorModule(this.getClass().getSimpleName())
                    .bag(
                            Map.of(
                                    "transactionNumber", tx.getTransactionInternalNumber()
                            )
                    )
                    .build();

            tx.addViolation(v);
        }
        if(Optional.ofNullable(tx.getTransactionInternalNumber()).orElse("").isEmpty()) {
            TransactionViolation v = TransactionViolation.builder()
                    .code(TX_INTERNAL_NUMBER_MUST_BE_PRESENT)
                    .severity(ERROR)
                    .source(ERP)
                    .processorModule(this.getClass().getSimpleName())
                    .build();
            tx.addViolation(v);
        }
        if(Optional.ofNullable(tx.getEntryDate()).orElse(LocalDate.of(1900, 1, 1)).isBefore(LocalDate.of(1990, 1, 1))) {
            TransactionViolation v = TransactionViolation.builder()
                    .code(ENTRY_DATE_MUST_BE_PRESENT)
                    .severity(ERROR)
                    .source(ERP)
                    .processorModule(this.getClass().getSimpleName())
                    .build();
            tx.addViolation(v);
        }
        for(TransactionItemEntity transactionItem : tx.getItems()) {
            transactionItem.getDocument().ifPresent(document -> {
                if(Optional.ofNullable(document.getNum()).orElse("").isEmpty()) {
                   TransactionViolation v = TransactionViolation.builder()
                           .code(DOCUMENT_NAME_MUST_BE_SET)
                           .severity(ERROR)
                           .source(ERP)
                           .txItemId(transactionItem.getId())
                           .processorModule(this.getClass().getSimpleName())
                            .bag(
                                      Map.of(
                                             "transactionNumber", tx.getTransactionInternalNumber()
                                      )
                            )
                           .build();
                    tx.addViolation(v);
                }
            });
            if(transactionItem.getFxRate().equals(BigDecimal.ZERO)) {
                TransactionViolation v = TransactionViolation.builder()
                        .code(FX_RATE_MUST_BE_GREATER_THAN_ZERO)
                        .severity(ERROR)
                        .source(ERP)
                        .txItemId(transactionItem.getId())
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
    }

}
