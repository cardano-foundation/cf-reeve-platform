package org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.Reconcilation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemExtractionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ExtractionTransactionItemView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ExtractionTransactionView;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class ExtractionItemService {
    private final TransactionItemExtractionRepository transactionItemRepositoryImpl;

    @Transactional(readOnly = true)
    public ExtractionTransactionView findTransactionItems(LocalDate dateFrom, LocalDate dateTo, List<String> accountCode, List<String> costCenter, List<String> project, List<String> accountType, List<String> accountSubType) {

        List<ExtractionTransactionItemView> transactionItem = transactionItemRepositoryImpl.findByItemAccount(dateFrom, dateTo, accountCode, costCenter, project,accountType,accountSubType).stream().map(this::extractionTransactionItemViewBuilder).collect(Collectors.toList());

        return ExtractionTransactionView.createSuccess(transactionItem);
    }

    @Transactional(readOnly = true)
    public List<ExtractionTransactionItemView> findTransactionItemsPublic(String orgId, LocalDate dateFrom, LocalDate dateTo, Set<String> event, Set<String> currency, Optional<BigDecimal> minAmount, Optional<BigDecimal> maxAmount, Set<String> transactionHash) {

        return transactionItemRepositoryImpl.findByItemAccountDate(orgId, dateFrom, dateTo, event, currency, minAmount, maxAmount, transactionHash).stream().map(item -> {
            return extractionTransactionItemViewBuilder(item);
        }).toList();

    }

    private ExtractionTransactionItemView extractionTransactionItemViewBuilder(TransactionItemEntity item) {
        return new ExtractionTransactionItemView(
                item.getId(),
                item.getTransaction().getTransactionInternalNumber(),
                item.getTransaction().getId(),
                item.getTransaction().getEntryDate(),
                item.getTransaction().getTransactionType(),
                item.getTransaction().getLedgerDispatchReceipt().map(LedgerDispatchReceipt::getPrimaryBlockchainHash).orElse(null),
                item.getTransaction().getReconcilation().flatMap(Reconcilation::getFinalStatus).orElse(ReconcilationCode.NOK),
                item.getAccountDebit().map(Account::getCode).orElse(null),
                item.getAccountDebit().flatMap(Account::getName).orElse(null),
                item.getAccountDebit().flatMap(Account::getRefCode).orElse(null),
                item.getAccountCredit().map(Account::getCode).orElse(null),
                item.getAccountCredit().flatMap(Account::getName).orElse(null),
                item.getAccountCredit().flatMap(Account::getRefCode).orElse(null),
                item.getAmountFcy(),
                item.getAmountLcy(),
                item.getFxRate(),
                item.getCostCenter().map(CostCenter::getCustomerCode).orElse(null),
                item.getCostCenter().flatMap(CostCenter::getExternalCustomerCode).orElse(null),
                item.getCostCenter().flatMap(CostCenter::getName).orElse(null),
                item.getProject().map(Project::getCustomerCode).orElse(null),
                item.getProject().flatMap(Project::getName).orElse(null),
                item.getProject().flatMap(Project::getExternalCustomerCode).orElse(null),
                item.getAccountEvent().map(AccountEvent::getCode).orElse(null),
                item.getAccountEvent().map(AccountEvent::getName).orElse(null),
                item.getDocument().map(Document::getNum).orElse(null),
                item.getDocument().map(document -> document.getCurrency().getCustomerCode()).orElse(null),
                item.getDocument().flatMap(document -> document.getVat().map(Vat::getCustomerCode)).orElse(null),
                item.getDocument().flatMap(document -> document.getVat().flatMap(Vat::getRate)).orElse(ZERO),
                item.getDocument().flatMap(d -> d.getCounterparty().map(Counterparty::getCustomerCode)).orElse(null),
                item.getDocument().flatMap(d -> d.getCounterparty().map(Counterparty::getType)).isPresent() ? item.getDocument().flatMap(d -> d.getCounterparty().map(Counterparty::getType)).map(Object::toString).orElse(null) : null,
                item.getDocument().flatMap(document -> document.getCounterparty().flatMap(Counterparty::getName)).orElse(null),
                item.getRejection().map(Rejection::getRejectionReason).orElse(null)
        );
    }
}
