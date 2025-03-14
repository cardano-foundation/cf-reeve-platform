package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType.CREDIT;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType.DEBIT;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.LOB;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.CHART_OF_ACCOUNT_NOT_FOUND;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.EVENT_DATA_NOT_FOUND;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.ERROR;

import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.val;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccount;

@RequiredArgsConstructor
public class AccountEventCodesConversionTaskItem implements PipelineTaskItem {

    private final OrganisationPublicApiIF organisationPublicApi;

    @Override
    public void run(TransactionEntity tx) {
        val organisationId = tx.getOrganisation().getId();

        for (val item : tx.getItems()) {
            processAccountCode(DEBIT, item.getAccountDebit(), organisationId, item, tx);
            processAccountCode(CREDIT, item.getAccountCredit(), organisationId, item, tx);

            setAccountEventCode(organisationId, tx, item);
        }
    }

    private void processAccountCode(OperationType type,
                                    Optional<Account> accountCodeM,
                                    String organisationId,
                                    TransactionItemEntity item,
                                    TransactionEntity tx) {
        accountCodeM.ifPresent(acc -> {
            val accountCode = acc.getCode().trim();

            if (isEmpty(accountCode)) {
                return;
            }

            val accountChartMappingM = organisationPublicApi.getChartOfAccounts(organisationId, accountCode);

            accountChartMappingM.ifPresentOrElse(
                    chartOfAccount -> setAccountCodeRef(acc, type, item, chartOfAccount),
                    () -> addMissingChartOfAccountViolation(accountCode, type, item, tx, LOB)
            );
        });
    }

    private void setAccountCodeRef(Account account,
                                   OperationType type,
                                   TransactionItemEntity item,
                                   OrganisationChartOfAccount chartOfAccount) {

        switch (type) {
            case DEBIT:
                item.setAccountDebit(Optional.of(account.toBuilder()
                        .refCode(chartOfAccount.getEventRefCode())
                        .build()));
                break;
            case CREDIT:
                item.setAccountCredit(Optional.of(account.toBuilder().refCode(chartOfAccount.getEventRefCode()).build()));
                break;
        }
    }

    private void addMissingChartOfAccountViolation(String accountCode,
                                                   OperationType type,
                                                   TransactionItemEntity item,
                                                   TransactionEntity tx,
                                                   Source source) {
        val violation = TransactionViolation.builder()
                .txItemId(item.getId())
                .code(CHART_OF_ACCOUNT_NOT_FOUND)
                .subCode(type.name())
                .severity(ERROR)
                .source(source)
                .processorModule(this.getClass().getSimpleName())
                .bag(Map.of(
                        "accountCode", accountCode,
                        "type", type.name(),
                        "transactionNumber", tx.getTransactionInternalNumber()
                ))
                .build();

        tx.addViolation(violation);
    }

    private void addMissingEventViolation(String eventCode,
                                          TransactionItemEntity item,
                                          TransactionEntity tx) {
        val violation = TransactionViolation.builder()
                .txItemId(item.getId())
                .code(EVENT_DATA_NOT_FOUND)
                .severity(ERROR)
                .source(LOB)
                .processorModule(this.getClass().getSimpleName())
                .bag(Map.of(
                        "eventCode", eventCode,
                        "transactionNumber", tx.getTransactionInternalNumber()
                ))
                .build();

        tx.addViolation(violation);
    }

    private void setAccountEventCode(String organisationId,
                                     TransactionEntity tx,
                                     TransactionItemEntity item) {
        val accountDebitRefCode = item.getAccountDebit().flatMap(Account::getRefCode);
        val accountCreditRefCode = item.getAccountCredit().flatMap(Account::getRefCode);

        if (accountDebitRefCode.isPresent() && accountCreditRefCode.isPresent()) {
            val eventCode = STR."\{accountDebitRefCode.orElseThrow()}\{accountCreditRefCode.orElseThrow()}";

            organisationPublicApi.findEventCode(organisationId, accountDebitRefCode.get(), accountCreditRefCode.get())
                    .ifPresentOrElse(
                            event -> item.setAccountEvent(Optional.of(AccountEvent.builder()
                                    .code(eventCode)
                                    .name(event.getName())
                                    .build())
                            ),
                            () -> addMissingEventViolation(eventCode, item, tx));
        }
    }

}
