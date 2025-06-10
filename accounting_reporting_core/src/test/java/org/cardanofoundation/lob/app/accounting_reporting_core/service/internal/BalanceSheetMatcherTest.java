package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData;

class BalanceSheetMatcherTest {

    @Test
    void templateNotPresentTest() {
        Optional<BalanceSheetData> templateO = Optional.empty();
        BalanceSheetData actualMock = mock(BalanceSheetData.class);
        Optional<BalanceSheetData> actualO = Optional.of(actualMock);

        Assertions.assertTrue(BalanceSheetMatcher.matches(templateO, actualO));
    }

    @Test
    void actualNotPresentTest() {
        BalanceSheetData templateMock = mock(BalanceSheetData.class);
        Optional<BalanceSheetData> templateO = Optional.of(templateMock);
        Optional<BalanceSheetData> actualO = Optional.empty();

        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));
    }

    @Test
    void assetsTest() {
        BalanceSheetData templateMock = mock(BalanceSheetData.class);
        BalanceSheetData actualMock = mock(BalanceSheetData.class);

        Optional<BalanceSheetData> templateO = Optional.of(templateMock);
        Optional<BalanceSheetData> actualO = Optional.of(actualMock);

        // both empty
        when(templateMock.getAssets()).thenReturn(Optional.empty());
        when(actualMock.getAssets()).thenReturn(Optional.empty());
        Assertions.assertTrue(BalanceSheetMatcher.matches(templateO, actualO));

        // actual not present
        BalanceSheetData.Assets templateAssetsMock = mock(BalanceSheetData.Assets.class);
        when(templateMock.getAssets()).thenReturn(Optional.of(templateAssetsMock));
        when(actualMock.getAssets()).thenReturn(Optional.empty());

        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));

        BalanceSheetData.Assets actualAssetsMock = mock(BalanceSheetData.Assets.class);
        when(actualMock.getAssets()).thenReturn(Optional.of(actualAssetsMock));

        // Non current template not present
        when(templateAssetsMock.getNonCurrentAssets()).thenReturn(Optional.empty());
        when(actualAssetsMock.getNonCurrentAssets()).thenReturn(Optional.empty());
        Assertions.assertTrue(BalanceSheetMatcher.matches(templateO, actualO));

        // actual not present
        BalanceSheetData.Assets.NonCurrentAssets templateNonCurrent = mock(BalanceSheetData.Assets.NonCurrentAssets.class);
        when(templateAssetsMock.getNonCurrentAssets()).thenReturn(Optional.of(templateNonCurrent));
        when(actualAssetsMock.getNonCurrentAssets()).thenReturn(Optional.empty());
        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));

        BalanceSheetData.Assets.NonCurrentAssets actualNonCurrent = mock(BalanceSheetData.Assets.NonCurrentAssets.class);
        when(actualAssetsMock.getNonCurrentAssets()).thenReturn(Optional.of(actualNonCurrent));

        // all zero
        when(templateNonCurrent.getFinancialAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualNonCurrent.getFinancialAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateNonCurrent.getInvestments()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualNonCurrent.getInvestments()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateNonCurrent.getIntangibleAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualNonCurrent.getIntangibleAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateNonCurrent.getTangibleAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualNonCurrent.getTangibleAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertTrue(BalanceSheetMatcher.matches(templateO, actualO));
        // first doesn't match
        when(templateNonCurrent.getTangibleAssets()).thenReturn(Optional.of(BigDecimal.ONE));
        when(actualNonCurrent.getTangibleAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));
        when(templateNonCurrent.getFinancialAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualNonCurrent.getFinancialAssets()).thenReturn(Optional.of(BigDecimal.ZERO));

        // second doesn't match
        when(templateNonCurrent.getTangibleAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualNonCurrent.getTangibleAssets()).thenReturn(Optional.of(BigDecimal.ONE));
        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));
        when(templateNonCurrent.getTangibleAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualNonCurrent.getTangibleAssets()).thenReturn(Optional.of(BigDecimal.ZERO));

        // Current template not present
        when(templateAssetsMock.getCurrentAssets()).thenReturn(Optional.empty());
        when(actualAssetsMock.getCurrentAssets()).thenReturn(Optional.empty());
        Assertions.assertTrue(BalanceSheetMatcher.matches(templateO, actualO));
        // actual not present
        BalanceSheetData.Assets.CurrentAssets templateCurrent = mock(BalanceSheetData.Assets.CurrentAssets.class);
        when(templateAssetsMock.getCurrentAssets()).thenReturn(Optional.of(templateCurrent));
        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));

        BalanceSheetData.Assets.CurrentAssets actualCurrent = mock(BalanceSheetData.Assets.CurrentAssets.class);
        when(actualAssetsMock.getCurrentAssets()).thenReturn(Optional.of(actualCurrent));

        // all zero
        when(templateCurrent.getCashAndCashEquivalents()).thenReturn(Optional.of(BigDecimal.ONE));
        when(actualCurrent.getCashAndCashEquivalents()).thenReturn(Optional.of(BigDecimal.ONE));
        when(templateCurrent.getCryptoAssets()).thenReturn(Optional.of(BigDecimal.ONE));
        when(actualCurrent.getCryptoAssets()).thenReturn(Optional.of(BigDecimal.ONE));
        when(templateCurrent.getOtherReceivables()).thenReturn(Optional.of(BigDecimal.ONE));
        when(actualCurrent.getOtherReceivables()).thenReturn(Optional.of(BigDecimal.ONE));
        when(templateCurrent.getPrepaymentsAndOtherShortTermAssets()).thenReturn(Optional.of(BigDecimal.ONE));
        when(actualCurrent.getPrepaymentsAndOtherShortTermAssets()).thenReturn(Optional.of(BigDecimal.ONE));

        Assertions.assertTrue(BalanceSheetMatcher.matches(templateO, actualO));

        // first doesn't match
        when(templateCurrent.getPrepaymentsAndOtherShortTermAssets()).thenReturn(Optional.of(BigDecimal.ONE));
        when(actualCurrent.getPrepaymentsAndOtherShortTermAssets()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));
        when(templateCurrent.getCashAndCashEquivalents()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualCurrent.getCashAndCashEquivalents()).thenReturn(Optional.of(BigDecimal.ZERO));
    }

    @Test
    void liabilitiesTest() {
        BalanceSheetData templateMock = mock(BalanceSheetData.class);
        BalanceSheetData actualMock = mock(BalanceSheetData.class);

        Optional<BalanceSheetData> templateO = Optional.of(templateMock);
        Optional<BalanceSheetData> actualO = Optional.of(actualMock);

        // both empty
        when(templateMock.getLiabilities()).thenReturn(Optional.empty());
        when(actualMock.getLiabilities()).thenReturn(Optional.empty());
        Assertions.assertTrue(BalanceSheetMatcher.matches(templateO, actualO));

        // actual not present
        BalanceSheetData.Liabilities templateLiabilitiesMock = mock(BalanceSheetData.Liabilities.class);
        when(templateMock.getLiabilities()).thenReturn(Optional.of(templateLiabilitiesMock));
        when(actualMock.getLiabilities()).thenReturn(Optional.empty());

        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));

        BalanceSheetData.Liabilities actualLiabilitiesMock = mock(BalanceSheetData.Liabilities.class);
        when(actualMock.getLiabilities()).thenReturn(Optional.of(actualLiabilitiesMock));

        // Non current template not present
        when(templateLiabilitiesMock.getNonCurrentLiabilities()).thenReturn(Optional.empty());
        when(actualLiabilitiesMock.getNonCurrentLiabilities()).thenReturn(Optional.empty());
        Assertions.assertTrue(BalanceSheetMatcher.matches(templateO, actualO));

        // actual not present
        BalanceSheetData.Liabilities.NonCurrentLiabilities templateNonCurrent = mock(BalanceSheetData.Liabilities.NonCurrentLiabilities.class);
        when(templateLiabilitiesMock.getNonCurrentLiabilities()).thenReturn(Optional.of(templateNonCurrent));
        when(actualLiabilitiesMock.getNonCurrentLiabilities()).thenReturn(Optional.empty());
        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));

        BalanceSheetData.Liabilities.NonCurrentLiabilities actualNonCurrent = mock(BalanceSheetData.Liabilities.NonCurrentLiabilities.class);
        when(actualLiabilitiesMock.getNonCurrentLiabilities()).thenReturn(Optional.of(actualNonCurrent));

        // all zero
        when(templateNonCurrent.getProvisions()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualNonCurrent.getProvisions()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertTrue(BalanceSheetMatcher.matches(templateO, actualO));
        // first doesn't match
        when(templateNonCurrent.getProvisions()).thenReturn(Optional.of(BigDecimal.ONE));
        when(actualNonCurrent.getProvisions()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));
        when(templateNonCurrent.getProvisions()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualNonCurrent.getProvisions()).thenReturn(Optional.of(BigDecimal.ZERO));

        // Current template not present
        when(templateLiabilitiesMock.getCurrentLiabilities()).thenReturn(Optional.empty());
        when(actualLiabilitiesMock.getCurrentLiabilities()).thenReturn(Optional.empty());
        Assertions.assertTrue(BalanceSheetMatcher.matches(templateO, actualO));
        // actual not present
        BalanceSheetData.Liabilities.CurrentLiabilities templateCurrent = mock(BalanceSheetData.Liabilities.CurrentLiabilities.class);
        when(templateLiabilitiesMock.getCurrentLiabilities()).thenReturn(Optional.of(templateCurrent));
        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));
        BalanceSheetData.Liabilities.CurrentLiabilities actualCurrent = mock(BalanceSheetData.Liabilities.CurrentLiabilities.class);
        when(actualLiabilitiesMock.getCurrentLiabilities()).thenReturn(Optional.of(actualCurrent));

        // all zero
        when(templateCurrent.getTradeAccountsPayables()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualCurrent.getTradeAccountsPayables()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateCurrent.getOtherShortTermLiabilities()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualCurrent.getOtherShortTermLiabilities()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateCurrent.getAccrualsAndShortTermProvisions()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualCurrent.getAccrualsAndShortTermProvisions()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertTrue(BalanceSheetMatcher.matches(templateO, actualO));

        // first doesn't match
        when(templateCurrent.getTradeAccountsPayables()).thenReturn(Optional.of(BigDecimal.ONE));
        when(actualCurrent.getTradeAccountsPayables()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));
        when(templateCurrent.getTradeAccountsPayables()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualCurrent.getTradeAccountsPayables()).thenReturn(Optional.of(BigDecimal.ZERO));

        // second doesn't match
        when(templateCurrent.getOtherShortTermLiabilities()).thenReturn(Optional.of(BigDecimal.ONE));
        when(actualCurrent.getOtherShortTermLiabilities()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));
        when(templateCurrent.getOtherShortTermLiabilities()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualCurrent.getOtherShortTermLiabilities()).thenReturn(Optional.of(BigDecimal.ZERO));
    }

    @Test
    void capitalTest() {
        BalanceSheetData templateMock = mock(BalanceSheetData.class);
        BalanceSheetData actualMock = mock(BalanceSheetData.class);

        Optional<BalanceSheetData> templateO = Optional.of(templateMock);
        Optional<BalanceSheetData> actualO = Optional.of(actualMock);

        // both empty
        when(templateMock.getCapital()).thenReturn(Optional.empty());
        when(actualMock.getCapital()).thenReturn(Optional.empty());
        Assertions.assertTrue(BalanceSheetMatcher.matches(templateO, actualO));

        // actual not present
        BalanceSheetData.Capital templateCapitalMock = mock(BalanceSheetData.Capital.class);
        when(templateMock.getCapital()).thenReturn(Optional.of(templateCapitalMock));
        when(actualMock.getCapital()).thenReturn(Optional.empty());

        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));

        BalanceSheetData.Capital actualCapitalMock = mock(BalanceSheetData.Capital.class);
        when(actualMock.getCapital()).thenReturn(Optional.of(actualCapitalMock));

        // all zero
        when(templateCapitalMock.getCapital()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualCapitalMock.getCapital()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateCapitalMock.getProfitForTheYear()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualCapitalMock.getProfitForTheYear()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(templateCapitalMock.getResultsCarriedForward()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualCapitalMock.getResultsCarriedForward()).thenReturn(Optional.of(BigDecimal.ZERO));

        Assertions.assertTrue(BalanceSheetMatcher.matches(templateO, actualO));

        // first doesn't match
        when(templateCapitalMock.getCapital()).thenReturn(Optional.of(BigDecimal.ONE));
        when(actualCapitalMock.getCapital()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));
        when(templateCapitalMock.getCapital()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualCapitalMock.getCapital()).thenReturn(Optional.of(BigDecimal.ZERO));

        // second doesn't match
        when(templateCapitalMock.getProfitForTheYear()).thenReturn(Optional.of(BigDecimal.ONE));
        when(actualCapitalMock.getProfitForTheYear()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));
        when(templateCapitalMock.getProfitForTheYear()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualCapitalMock.getProfitForTheYear()).thenReturn(Optional.of(BigDecimal.ZERO));

        // third doesn't match
        when(templateCapitalMock.getResultsCarriedForward()).thenReturn(Optional.of(BigDecimal.ONE));
        when(actualCapitalMock.getResultsCarriedForward()).thenReturn(Optional.of(BigDecimal.ZERO));
        Assertions.assertFalse(BalanceSheetMatcher.matches(templateO, actualO));
        when(templateCapitalMock.getResultsCarriedForward()).thenReturn(Optional.of(BigDecimal.ZERO));
        when(actualCapitalMock.getResultsCarriedForward()).thenReturn(Optional.of(BigDecimal.ZERO));
    }

}
