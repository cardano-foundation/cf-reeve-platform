package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData;

class IncomeStatementMatcherTest {

    @Test
    void templateNotPresentTest() {
        Optional<IncomeStatementData> templateO = Optional.empty();
        IncomeStatementData actualMock = mock(IncomeStatementData.class);
        Optional<IncomeStatementData> actualO = Optional.of(actualMock);

        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));
    }

    @Test
    void actualNotPresentTest() {
        IncomeStatementData templateMock = mock(IncomeStatementData.class);
        Optional<IncomeStatementData> templateO = Optional.of(templateMock);
        Optional<IncomeStatementData> actualO = Optional.empty();

        Assertions.assertFalse(IncomeStatementMatcher.matches(templateO, actualO));
    }

    @Test
    void revenuesTest() {
        IncomeStatementData templateMock = mock(IncomeStatementData.class);
        IncomeStatementData actualMock = mock(IncomeStatementData.class);
        Optional<IncomeStatementData> templateO = Optional.of(templateMock);
        Optional<IncomeStatementData> actualO = Optional.of(actualMock);

        // both not present
        when(templateMock.getRevenues()).thenReturn(Optional.empty());
        when(actualMock.getRevenues()).thenReturn(Optional.empty());
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        IncomeStatementData.Revenues templateRevenueMock = mock(IncomeStatementData.Revenues.class);
        // actual not present
        when(templateMock.getRevenues()).thenReturn(Optional.of(templateRevenueMock));
        when(actualMock.getRevenues()).thenReturn(Optional.empty());
        Assertions.assertFalse(IncomeStatementMatcher.matches(templateO, actualO));

        IncomeStatementData.Revenues actualRevenueMock = mock(IncomeStatementData.Revenues.class);
        when(actualMock.getRevenues()).thenReturn(Optional.of(actualRevenueMock));

        // All Zero values
        when(templateRevenueMock.getOtherIncome()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getOtherIncome()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getBuildOfLongTermProvision()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getBuildOfLongTermProvision()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        // All Non Zero values
        when(templateRevenueMock.getOtherIncome()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getOtherIncome()).thenReturn(Optional.of(BigDecimal.TEN));
        when(templateRevenueMock.getBuildOfLongTermProvision()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getBuildOfLongTermProvision()).thenReturn(Optional.of(BigDecimal.TEN));
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        // First Value not equal
        when(templateRevenueMock.getOtherIncome()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getOtherIncome()).thenReturn(Optional.of(BigDecimal.ONE));
        when(templateRevenueMock.getBuildOfLongTermProvision()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getBuildOfLongTermProvision()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertFalse(IncomeStatementMatcher.matches(templateO, actualO));

        // Second Value not equal
        when(templateRevenueMock.getOtherIncome()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getOtherIncome()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getBuildOfLongTermProvision()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getBuildOfLongTermProvision()).thenReturn(Optional.of(BigDecimal.ONE));
    }

    @Test
    void costOfGoodsAndServicesTest() {
        IncomeStatementData templateMock = mock(IncomeStatementData.class);
        IncomeStatementData actualMock = mock(IncomeStatementData.class);
        Optional<IncomeStatementData> templateO = Optional.of(templateMock);
        Optional<IncomeStatementData> actualO = Optional.of(actualMock);

        // both not present
        when(templateMock.getCostOfGoodsAndServices()).thenReturn(Optional.empty());
        when(actualMock.getCostOfGoodsAndServices()).thenReturn(Optional.empty());
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        IncomeStatementData.CostOfGoodsAndServices templateRevenueMock = mock(IncomeStatementData.CostOfGoodsAndServices.class);
        // actual not present
        when(templateMock.getCostOfGoodsAndServices()).thenReturn(Optional.of(templateRevenueMock));
        when(actualMock.getCostOfGoodsAndServices()).thenReturn(Optional.empty());
        Assertions.assertFalse(IncomeStatementMatcher.matches(templateO, actualO));

        IncomeStatementData.CostOfGoodsAndServices actualRevenueMock = mock(IncomeStatementData.CostOfGoodsAndServices.class);
        when(actualMock.getCostOfGoodsAndServices()).thenReturn(Optional.of(actualRevenueMock));

        // All Zero values
        when(templateRevenueMock.getExternalServices()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getExternalServices()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        // All Non Zero values
        when(templateRevenueMock.getExternalServices()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getExternalServices()).thenReturn(Optional.of(BigDecimal.TEN));
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        // First Value not equal
        when(templateRevenueMock.getExternalServices()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getExternalServices()).thenReturn(Optional.of(BigDecimal.ONE));
    }

    @Test
    void operatingExpensesTest() {
        IncomeStatementData templateMock = mock(IncomeStatementData.class);
        IncomeStatementData actualMock = mock(IncomeStatementData.class);
        Optional<IncomeStatementData> templateO = Optional.of(templateMock);
        Optional<IncomeStatementData> actualO = Optional.of(actualMock);

        // both not present
        when(templateMock.getOperatingExpenses()).thenReturn(Optional.empty());
        when(actualMock.getOperatingExpenses()).thenReturn(Optional.empty());
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        IncomeStatementData.OperatingExpenses templateRevenueMock = mock(IncomeStatementData.OperatingExpenses.class);
        // actual not present
        when(templateMock.getOperatingExpenses()).thenReturn(Optional.of(templateRevenueMock));
        when(actualMock.getOperatingExpenses()).thenReturn(Optional.empty());
        Assertions.assertFalse(IncomeStatementMatcher.matches(templateO, actualO));

        IncomeStatementData.OperatingExpenses actualRevenueMock = mock(IncomeStatementData.OperatingExpenses.class);
        when(actualMock.getOperatingExpenses()).thenReturn(Optional.of(actualRevenueMock));

        // All Zero values
        when(templateRevenueMock.getAmortizationOnIntangibleAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getAmortizationOnIntangibleAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getDepreciationAndImpairmentLossesOnTangibleAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getDepreciationAndImpairmentLossesOnTangibleAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getRentExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getRentExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getPersonnelExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getPersonnelExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getGeneralAndAdministrativeExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getGeneralAndAdministrativeExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        // All Non Zero values
        when(templateRevenueMock.getAmortizationOnIntangibleAssets()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getAmortizationOnIntangibleAssets()).thenReturn(Optional.of(BigDecimal.TEN));
        when(templateRevenueMock.getDepreciationAndImpairmentLossesOnTangibleAssets()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getDepreciationAndImpairmentLossesOnTangibleAssets()).thenReturn(Optional.of(BigDecimal.TEN));
        when(templateRevenueMock.getRentExpenses()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getRentExpenses()).thenReturn(Optional.of(BigDecimal.TEN));
        when(templateRevenueMock.getPersonnelExpenses()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getPersonnelExpenses()).thenReturn(Optional.of(BigDecimal.TEN));
        when(templateRevenueMock.getGeneralAndAdministrativeExpenses()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getGeneralAndAdministrativeExpenses()).thenReturn(Optional.of(BigDecimal.TEN));
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        // First Value not equal
        when(templateRevenueMock.getAmortizationOnIntangibleAssets()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getAmortizationOnIntangibleAssets()).thenReturn(Optional.of(BigDecimal.ONE));
        when(templateRevenueMock.getDepreciationAndImpairmentLossesOnTangibleAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getDepreciationAndImpairmentLossesOnTangibleAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getRentExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getRentExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getPersonnelExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getPersonnelExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getGeneralAndAdministrativeExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getGeneralAndAdministrativeExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertFalse(IncomeStatementMatcher.matches(templateO, actualO));
    }

    @Test
    void financialIncomeTest() {
        IncomeStatementData templateMock = mock(IncomeStatementData.class);
        IncomeStatementData actualMock = mock(IncomeStatementData.class);
        Optional<IncomeStatementData> templateO = Optional.of(templateMock);
        Optional<IncomeStatementData> actualO = Optional.of(actualMock);

        // both not present
        when(templateMock.getFinancialIncome()).thenReturn(Optional.empty());
        when(actualMock.getFinancialIncome()).thenReturn(Optional.empty());
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        IncomeStatementData.FinancialIncome templateRevenueMock = mock(IncomeStatementData.FinancialIncome.class);
        // actual not present
        when(templateMock.getFinancialIncome()).thenReturn(Optional.of(templateRevenueMock));
        when(actualMock.getFinancialIncome()).thenReturn(Optional.empty());
        Assertions.assertFalse(IncomeStatementMatcher.matches(templateO, actualO));

        IncomeStatementData.FinancialIncome actualRevenueMock = mock(IncomeStatementData.FinancialIncome.class);
        when(actualMock.getFinancialIncome()).thenReturn(Optional.of(actualRevenueMock));

        // All Zero values
        when(templateRevenueMock.getFinancialExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getFinancialExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getFinancialRevenues()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getFinancialRevenues()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getNetIncomeOptionsSale()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getNetIncomeOptionsSale()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getStakingRewardsIncome()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getStakingRewardsIncome()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getRealisedGainsOnSaleOfCryptocurrencies()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getRealisedGainsOnSaleOfCryptocurrencies()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        // All Non Zero values
        when(templateRevenueMock.getFinancialExpenses()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getFinancialExpenses()).thenReturn(Optional.of(BigDecimal.TEN));
        when(templateRevenueMock.getFinancialRevenues()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getFinancialRevenues()).thenReturn(Optional.of(BigDecimal.TEN));
        when(templateRevenueMock.getNetIncomeOptionsSale()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getNetIncomeOptionsSale()).thenReturn(Optional.of(BigDecimal.TEN));
        when(templateRevenueMock.getStakingRewardsIncome()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getStakingRewardsIncome()).thenReturn(Optional.of(BigDecimal.TEN));
        when(templateRevenueMock.getRealisedGainsOnSaleOfCryptocurrencies()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getRealisedGainsOnSaleOfCryptocurrencies()).thenReturn(Optional.of(BigDecimal.TEN));
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        // First Value not equal
        when(templateRevenueMock.getFinancialExpenses()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getFinancialExpenses()).thenReturn(Optional.of(BigDecimal.ONE));
        when(templateRevenueMock.getFinancialRevenues()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getFinancialRevenues()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getNetIncomeOptionsSale()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getNetIncomeOptionsSale()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getStakingRewardsIncome()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getStakingRewardsIncome()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateRevenueMock.getRealisedGainsOnSaleOfCryptocurrencies()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getRealisedGainsOnSaleOfCryptocurrencies()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertFalse(IncomeStatementMatcher.matches(templateO, actualO));
    }

    @Test
    void extraordinaryIncomeTest() {
        IncomeStatementData templateMock = mock(IncomeStatementData.class);
        IncomeStatementData actualMock = mock(IncomeStatementData.class);
        Optional<IncomeStatementData> templateO = Optional.of(templateMock);
        Optional<IncomeStatementData> actualO = Optional.of(actualMock);

        // both not present
        when(templateMock.getExtraordinaryIncome()).thenReturn(Optional.empty());
        when(actualMock.getExtraordinaryIncome()).thenReturn(Optional.empty());
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        IncomeStatementData.ExtraordinaryIncome templateRevenueMock = mock(IncomeStatementData.ExtraordinaryIncome.class);
        // actual not present
        when(templateMock.getExtraordinaryIncome()).thenReturn(Optional.of(templateRevenueMock));
        when(actualMock.getExtraordinaryIncome()).thenReturn(Optional.empty());
        Assertions.assertFalse(IncomeStatementMatcher.matches(templateO, actualO));

        IncomeStatementData.ExtraordinaryIncome actualRevenueMock = mock(IncomeStatementData.ExtraordinaryIncome.class);
        when(actualMock.getExtraordinaryIncome()).thenReturn(Optional.of(actualRevenueMock));

        // All Zero values
        when(templateRevenueMock.getExtraordinaryExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getExtraordinaryExpenses()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        // All Non Zero values
        when(templateRevenueMock.getExtraordinaryExpenses()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getExtraordinaryExpenses()).thenReturn(Optional.of(BigDecimal.TEN));
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        // First Value not equal
        when(templateRevenueMock.getExtraordinaryExpenses()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getExtraordinaryExpenses()).thenReturn(Optional.of(BigDecimal.ONE));
        Assertions.assertFalse(IncomeStatementMatcher.matches(templateO, actualO));
    }

    @Test
    void taxExpensesTest() {
        IncomeStatementData templateMock = mock(IncomeStatementData.class);
        IncomeStatementData actualMock = mock(IncomeStatementData.class);
        Optional<IncomeStatementData> templateO = Optional.of(templateMock);
        Optional<IncomeStatementData> actualO = Optional.of(actualMock);

        // both not present
        when(templateMock.getTaxExpenses()).thenReturn(Optional.empty());
        when(actualMock.getTaxExpenses()).thenReturn(Optional.empty());
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        IncomeStatementData.TaxExpenses templateRevenueMock = mock(IncomeStatementData.TaxExpenses.class);
        // actual not present
        when(templateMock.getTaxExpenses()).thenReturn(Optional.of(templateRevenueMock));
        when(actualMock.getTaxExpenses()).thenReturn(Optional.empty());
        Assertions.assertFalse(IncomeStatementMatcher.matches(templateO, actualO));

        IncomeStatementData.TaxExpenses actualRevenueMock = mock(IncomeStatementData.TaxExpenses.class);
        when(actualMock.getTaxExpenses()).thenReturn(Optional.of(actualRevenueMock));

        // All Zero values
        when(templateRevenueMock.getDirectTaxes()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getDirectTaxes()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        // All Non Zero values
        when(templateRevenueMock.getDirectTaxes()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getDirectTaxes()).thenReturn(Optional.of(BigDecimal.TEN));
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        // First Value not equal
        when(templateRevenueMock.getDirectTaxes()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualRevenueMock.getDirectTaxes()).thenReturn(Optional.of(BigDecimal.ONE));
        Assertions.assertFalse(IncomeStatementMatcher.matches(templateO, actualO));

        // Second Value not equal
        when(templateRevenueMock.getDirectTaxes()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualRevenueMock.getDirectTaxes()).thenReturn(Optional.of(BigDecimal.ONE));
        Assertions.assertFalse(IncomeStatementMatcher.matches(templateO, actualO));
    }

    @Test
    void profitForTheYearTest() {
        IncomeStatementData templateMock = mock(IncomeStatementData.class);
        IncomeStatementData actualMock = mock(IncomeStatementData.class);
        Optional<IncomeStatementData> templateO = Optional.of(templateMock);
        Optional<IncomeStatementData> actualO = Optional.of(actualMock);

        // both not present
        when(templateMock.getProfitForTheYear()).thenReturn(Optional.empty());
        when(actualMock.getProfitForTheYear()).thenReturn(Optional.empty());
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        // actual not present
        when(templateMock.getProfitForTheYear()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualMock.getProfitForTheYear()).thenReturn(Optional.empty());
        Assertions.assertFalse(IncomeStatementMatcher.matches(templateO, actualO));

        // All Zero values
        when(templateMock.getProfitForTheYear()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualMock.getProfitForTheYear()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        // All Non Zero values
        when(templateMock.getProfitForTheYear()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualMock.getProfitForTheYear()).thenReturn(Optional.of(BigDecimal.TEN));
        Assertions.assertTrue(IncomeStatementMatcher.matches(templateO, actualO));

        // First Value not equal
        when(templateMock.getProfitForTheYear()).thenReturn(Optional.of(BigDecimal.TEN));
        when(actualMock.getProfitForTheYear()).thenReturn(Optional.of(BigDecimal.ONE));
        Assertions.assertFalse(IncomeStatementMatcher.matches(templateO, actualO));
    }

}
