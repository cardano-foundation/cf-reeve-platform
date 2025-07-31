package org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.Reconcilation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemExtractionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ExtractionTransactionItemView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ExtractionTransactionView;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;
import org.cardanofoundation.lob.app.organisation.domain.entity.Project;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class ExtractionItemService {
    private final TransactionItemExtractionRepository transactionItemRepositoryImpl;
    private final OrganisationPublicApi organisationPublicApi;

    @Transactional(readOnly = true)
    public ExtractionTransactionView findTransactionItems(LocalDate dateFrom, LocalDate dateTo, List<String> accountCode, List<String> costCenter, List<String> project, List<String> accountType, List<String> accountSubType) {

        List<ExtractionTransactionItemView> transactionItem = transactionItemRepositoryImpl.findByItemAccount(dateFrom, dateTo, accountCode, costCenter, project, accountType, accountSubType)
                .stream().map(this::extractionTransactionItemViewBuilder).toList();

        return ExtractionTransactionView.createSuccess(transactionItem, transactionItem.size(), 0, transactionItem.size());
    }

    @Transactional(readOnly = true)
    public ExtractionTransactionView findTransactionItemsPublic(String orgId, LocalDate dateFrom, LocalDate dateTo, Set<String> event, Set<String> currency, Optional<BigDecimal> minAmount, Optional<BigDecimal> maxAmount, Set<String> transactionHash, int page, int limit) {

        List<ExtractionTransactionItemView> transactionItemViews = transactionItemRepositoryImpl.findByItemAccountDateAggregated(orgId, dateFrom, dateTo, event, currency, minAmount, maxAmount, transactionHash, page, limit).stream().map(item -> enrichTransactionItemViewBuilder(extractionTransactionItemViewBuilder(item))).toList();
        long countTotalElements = transactionItemRepositoryImpl.countItemsByAccountDateAggregated(orgId, dateFrom, dateTo, event, currency, minAmount, maxAmount, transactionHash);
        return ExtractionTransactionView.createSuccess(transactionItemViews, countTotalElements, page, limit);
    }

    private ExtractionTransactionItemView extractionTransactionItemViewBuilder(TransactionItemEntity item) {
        Optional<CostCenter> costCenter = organisationPublicApi.findCostCenter(item.getTransaction().getOrganisation().getId(), item.getCostCenter().map(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter::getCustomerCode).orElse(null));
        Optional<Project> project = organisationPublicApi.findProject(item.getTransaction().getOrganisation().getId(), item.getProject().map(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Project::getCustomerCode).orElse(null));
        return new ExtractionTransactionItemView(
                item.getId(),
                item.getTransaction().getInternalTransactionNumber(),
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
                item.getTransaction().getTransactionType().equals(TransactionType.FxRevaluation) ? item.getAmountLcy() : item.getAmountFcy(),
                item.getAmountLcy(),
                item.getFxRate(),
                item.getCostCenter().map(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter::getCustomerCode).orElse(null),
                item.getCostCenter().flatMap(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter::getName).orElse(null),
                item.getProject().map(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Project::getCustomerCode).orElse(null),
                item.getProject().flatMap(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Project::getName).orElse(null),
                item.getAccountEvent().map(AccountEvent::getCode).orElse(null),
                item.getAccountEvent().map(AccountEvent::getName).orElse(null),
                item.getDocument().map(Document::getNum).orElse(null),
                item.getDocument().map(document -> document.getCurrency().getCustomerCode()).orElse(null),
                item.getDocument().flatMap(document -> document.getVat().map(Vat::getCustomerCode)).orElse(null),
                item.getDocument().flatMap(document -> document.getVat().flatMap(Vat::getRate)).orElse(ZERO),
                item.getDocument().flatMap(d -> d.getCounterparty().map(Counterparty::getCustomerCode)).orElse(null),
                item.getDocument().flatMap(d -> d.getCounterparty().map(Counterparty::getType)).isPresent() ? item.getDocument().flatMap(d -> d.getCounterparty().map(Counterparty::getType)).map(Object::toString).orElse(null) : null,
                item.getDocument().flatMap(document -> document.getCounterparty().flatMap(Counterparty::getName)).orElse(null),
                item.getRejection().map(Rejection::getRejectionReason).orElse(null),
                costCenter.map(CostCenter::getParentCustomerCode).orElse(null),
                costCenter.flatMap(organisationCostCenter -> organisationCostCenter.getParent()).flatMap(parentCostCenter -> Optional.ofNullable(parentCostCenter.getName())).orElse(null),
                project.flatMap(organisationProject -> organisationProject.getParent()).flatMap(parentProject -> Optional.ofNullable(parentProject.getId().getCustomerCode())).orElse(null),
                project.flatMap(organisationProject -> organisationProject.getParent()).flatMap(parentProject -> Optional.ofNullable(parentProject.getName())).orElse(null)
        );
    }

    private ExtractionTransactionItemView enrichTransactionItemViewBuilder(ExtractionTransactionItemView item) {

        item.setCostCenterCustomerCode(item.getParentCostCenterCustomerCode());
        item.setCostCenterName(item.getParentCostCenterName());
        item.setProjectCustomerCode(item.getParentProjectCustomerCode());
        item.setProjectName(item.getParentProjectName());

        item.setParentCostCenterName(null);
        item.setParentCostCenterCustomerCode(null);
        item.setParentProjectName(null);
        item.setParentProjectCustomerCode(null);

        return item;
    }
}
