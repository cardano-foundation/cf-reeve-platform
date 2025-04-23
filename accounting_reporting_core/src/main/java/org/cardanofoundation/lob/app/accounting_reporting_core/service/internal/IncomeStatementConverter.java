package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import java.util.Optional;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData;

@Service("accounting_reporting_core.IncomeStatementConverter")
@RequiredArgsConstructor
public class IncomeStatementConverter {

    public Optional<org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IncomeStatementData> convertIncomeStatement(Optional<org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData> incomeStatementData) {
        return incomeStatementData.map(entityData -> {
            return org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IncomeStatementData.builder()
                    .revenues(convertRevenues(entityData.getRevenues()).orElse(null))
                    .costOfServicesAndGoods(convertCostOfServicesAndGoods(entityData.getCostOfGoodsAndServices()).orElse(null))
                    .operatingExpenses(convertOperatingExpenses(entityData.getOperatingExpenses()).orElse(null))
                    .financialIncome(convertFinancialIncome(entityData.getFinancialIncome()).orElse(null))
                    .taxExpenses(convertTaxExpenses(entityData.getTaxExpenses()).orElse(null))
                    .operatingExpenses(convertOperatingExpenses(entityData.getOperatingExpenses()).orElse(null))
                    .extraordinaryIncome(convertExtraordinaryIncome(entityData.getExtraordinaryIncome()).orElse(null))
                    .profitForTheYear(entityData.getProfitForTheYear().orElse(null))
                    .build();
        });
    }

    private Optional<Revenues> convertRevenues(Optional<IncomeStatementData.Revenues> revenues) {
        return revenues.map(r -> Revenues.builder()
                .otherIncome(r.getOtherIncome().orElse(null))
                .buildOfLongTermProvision(r.getBuildOfLongTermProvision().orElse(null))
                .build());
    }

    private Optional<CostOfServicesAndGoods> convertCostOfServicesAndGoods(Optional<IncomeStatementData.CostOfGoodsAndServices> costOfGoodsAndServices) {
        return costOfGoodsAndServices.map(c -> CostOfServicesAndGoods.builder()
                .costOfProvidingServices(c.getExternalServices().orElse(null))
                .build());
    }

    private Optional<OperatingExpenses> convertOperatingExpenses(Optional<IncomeStatementData.OperatingExpenses> operatingExpenses) {
        return operatingExpenses.map(oe -> OperatingExpenses.builder()
                .personnelExpenses(oe.getPersonnelExpenses().orElse(null))
                .generalAndAdministrativeExpenses(oe.getGeneralAndAdministrativeExpenses().orElse(null))
                .depreciationAndImpairmentLossesOnTangibleAssets(oe.getDepreciationAndImpairmentLossesOnTangibleAssets().orElse(null))
                .amortizationOnIntangibleAssets(oe.getAmortizationOnIntangibleAssets().orElse(null))
                .rentExpenses(oe.getRentExpenses().orElse(null))
                .build());
    }

    private Optional<FinancialIncome> convertFinancialIncome(Optional<IncomeStatementData.FinancialIncome> financialIncome) {
        return financialIncome.map(op -> FinancialIncome.builder()
                .financialRevenues(op.getFinancialRevenues().orElse(null))
                .financialExpenses(op.getFinancialExpenses().orElse(null))
                .realisedGainsOnSaleOfCryptocurrencies(op.getRealisedGainsOnSaleOfCryptocurrencies().orElse(null))
                .stakingRewardsIncome(op.getStakingRewardsIncome().orElse(null))
                .netIncomeOptionsSale(op.getNetIncomeOptionsSale().orElse(null))
                .build());
    }

    private Optional<ExtraordinaryIncome> convertExtraordinaryIncome(Optional<IncomeStatementData.ExtraordinaryIncome> financialIncome) {
        return financialIncome.map(op -> ExtraordinaryIncome.builder()
                .extraordinaryExpenses(op.getExtraordinaryExpenses().orElse(null))
                .build());
    }

    private Optional<TaxExpenses> convertTaxExpenses(Optional<IncomeStatementData.TaxExpenses> taxExpenses) {
        return taxExpenses.map(te -> TaxExpenses.builder()
                .incomeTaxExpense(te.getDirectTaxes().orElse(null))
                .build());
    }

}
