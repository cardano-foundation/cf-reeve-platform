package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.lang.Nullable;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData.ExtraordinaryIncome;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData.FinancialIncome;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData.OperatingExpenses;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData.Revenues;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData.TaxExpenses;

public class IncomeStatementMatcher {

    public static boolean matches(Optional<IncomeStatementData> templateO, Optional<IncomeStatementData> actualO) {
        if (templateO.isEmpty()) return true;
        if (actualO.isEmpty()) return false;
        IncomeStatementData template = templateO.get();
        IncomeStatementData actual = actualO.get();
        return matches(template.getRevenues().orElse(null), actual.getRevenues().orElse(null)) &&
                matches(template.getCostOfGoodsAndServices().orElse(null), actual.getCostOfGoodsAndServices().orElse(null)) &&
                matches(template.getOperatingExpenses().orElse(null), actual.getOperatingExpenses().orElse(null)) &&
                matches(template.getFinancialIncome().orElse(null), actual.getFinancialIncome().orElse(null)) &&
                matches(template.getExtraordinaryIncome().orElse(null), actual.getExtraordinaryIncome().orElse(null)) &&
                matches(template.getTaxExpenses().orElse(null), actual.getTaxExpenses().orElse(null)) &&
                matchesValue(template.getProfitForTheYear(), actual.getProfitForTheYear());
    }

    private static boolean matches(@Nullable TaxExpenses template, @Nullable TaxExpenses actual) {
        if (template == null) return true;
        if (actual == null) return false;
        return matchesValue(template.getDirectTaxes(), actual.getDirectTaxes());
    }

    private static boolean matches(@Nullable ExtraordinaryIncome template, @Nullable ExtraordinaryIncome actual) {
        if (template == null) return true;
        if (actual == null) return false;
        return matchesValue(template.getExtraordinaryExpenses(), actual.getExtraordinaryExpenses());
    }

    private static boolean matches(@Nullable OperatingExpenses template, @Nullable OperatingExpenses actual) {
        if (template == null) return true;
        if (actual == null) return false;
        return matchesValue(template.getAmortizationOnIntangibleAssets(), actual.getAmortizationOnIntangibleAssets()) &&
                matchesValue(template.getDepreciationAndImpairmentLossesOnTangibleAssets(), actual.getDepreciationAndImpairmentLossesOnTangibleAssets()) &&
                matchesValue(template.getRentExpenses(), actual.getRentExpenses()) &&
                matchesValue(template.getPersonnelExpenses(), actual.getPersonnelExpenses()) &&
                matchesValue(template.getGeneralAndAdministrativeExpenses(), actual.getGeneralAndAdministrativeExpenses());
    }

    private static boolean matches(@Nullable FinancialIncome template, @Nullable FinancialIncome actual) {
        if (template == null) return true;
        if (actual == null) return false;
        return matchesValue(template.getFinancialExpenses(), actual.getFinancialExpenses()) &&
                matchesValue(template.getFinancialRevenues(), actual.getFinancialRevenues()) &&
                matchesValue(template.getNetIncomeOptionsSale(), actual.getNetIncomeOptionsSale()) &&
                matchesValue(template.getStakingRewardsIncome(), actual.getStakingRewardsIncome()) &&
                matchesValue(template.getRealisedGainsOnSaleOfCryptocurrencies(), actual.getRealisedGainsOnSaleOfCryptocurrencies());
    }

    private static boolean matches(@Nullable IncomeStatementData.CostOfGoodsAndServices template, @Nullable IncomeStatementData.CostOfGoodsAndServices actual) {
        if (template == null) return true;
        if (actual == null) return false;
        return matchesValue(template.getExternalServices(), actual.getExternalServices());
    }

    private static boolean matches(@Nullable Revenues template, @Nullable Revenues actual) {
        if (template == null) return true;
        if (actual == null) return false;
        return matchesValue(template.getOtherIncome(), actual.getOtherIncome()) &&
                matchesValue(template.getBuildOfLongTermProvision(), actual.getBuildOfLongTermProvision());
    }



    private static boolean matchesValue(Optional<BigDecimal> expected, Optional<BigDecimal> actual) {
        Boolean b = expected.map(value -> actual.map(value::compareTo).orElse(-1) == 0).orElse(true);
        return b;
    }
}
