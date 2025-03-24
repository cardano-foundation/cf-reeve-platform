package org.cardanofoundation.lob.app.accounting_reporting_core.resource.model.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.CreateReportView;

@ExtendWith(MockitoExtension.class)
class CreateReportViewTest {

    private ReportRequest reportRequest;

    @BeforeEach
    void setUp() {
        reportRequest = mock(ReportRequest.class);
        when(reportRequest.getOrganisationID()).thenReturn("org-123");
    }

    @Test
    void fromReportRequest_WithBalanceSheet_ShouldPopulateBalanceSheetData() {
        when(reportRequest.getReportType()).thenReturn(ReportType.BALANCE_SHEET);
        when(reportRequest.getPropertyPlantEquipment()).thenReturn("1000");
        when(reportRequest.getIntangibleAssets()).thenReturn("500");
        when(reportRequest.getInvestments()).thenReturn("200");
        when(reportRequest.getFinancialAssets()).thenReturn("300");
        when(reportRequest.getPrepaymentsAndOtherShortTermAssets()).thenReturn("150");
        when(reportRequest.getOtherReceivables()).thenReturn("250");
        when(reportRequest.getCryptoAssets()).thenReturn("350");
        when(reportRequest.getCashAndCashEquivalents()).thenReturn("450");
        when(reportRequest.getProvisions()).thenReturn("50");
        when(reportRequest.getTradeAccountsPayables()).thenReturn("60");
        when(reportRequest.getOtherCurrentLiabilities()).thenReturn("70");
        when(reportRequest.getAccrualsAndShortTermProvisions()).thenReturn("80");
        when(reportRequest.getCapital()).thenReturn("1000");
        when(reportRequest.getProfitForTheYear()).thenReturn("500");
        when(reportRequest.getResultsCarriedForward()).thenReturn("200");

        CreateReportView view = CreateReportView.fromReportRequest(reportRequest);
        assertEquals("org-123", view.getOrganisationId());
        assertTrue(view.getBalanceSheetData().isPresent());

        BalanceSheetData data = view.getBalanceSheetData().get();

        // Validate Assets
        assertTrue(data.getAssets().isPresent());
        BalanceSheetData.Assets assets = data.getAssets().get();

        assertEquals(new BigDecimal("1000"), assets.getNonCurrentAssets().get().getPropertyPlantEquipment().get());
        assertEquals(new BigDecimal("500"), assets.getNonCurrentAssets().get().getIntangibleAssets().get());
        assertEquals(new BigDecimal("200"), assets.getNonCurrentAssets().get().getInvestments().get());
        assertEquals(new BigDecimal("300"), assets.getNonCurrentAssets().get().getFinancialAssets().get());
        assertEquals(new BigDecimal("150"), assets.getCurrentAssets().get().getPrepaymentsAndOtherShortTermAssets().get());
        assertEquals(new BigDecimal("250"), assets.getCurrentAssets().get().getOtherReceivables().get());
        assertEquals(new BigDecimal("350"), assets.getCurrentAssets().get().getCryptoAssets().get());
        assertEquals(new BigDecimal("450"), assets.getCurrentAssets().get().getCashAndCashEquivalents().get());

        // Validate Liabilities
        assertTrue(data.getLiabilities().isPresent());
        BalanceSheetData.Liabilities liabilities = data.getLiabilities().get();

        assertEquals(new BigDecimal("50"), liabilities.getNonCurrentLiabilities().get().getProvisions().get());
        assertEquals(new BigDecimal("60"), liabilities.getCurrentLiabilities().get().getTradeAccountsPayables().get());
        assertEquals(new BigDecimal("70"), liabilities.getCurrentLiabilities().get().getOtherCurrentLiabilities().get());
        assertEquals(new BigDecimal("80"), liabilities.getCurrentLiabilities().get().getAccrualsAndShortTermProvisions().get());

        // Validate Equity
        BalanceSheetData.Capital capital = data.getCapital().get();
        assertEquals(new BigDecimal("1000"), capital.getCapital().get());
        assertEquals(new BigDecimal("500"), capital.getProfitForTheYear().get());
        assertEquals(new BigDecimal("200"), capital.getResultsCarriedForward().get());
    }

    @Test
    void fromReportRequest_WithIncomeStatement_ShouldPopulateIncomeStatementData() {
        when(reportRequest.getReportType()).thenReturn(ReportType.INCOME_STATEMENT);
        when(reportRequest.getOtherIncome()).thenReturn("1000");
        when(reportRequest.getBuildOfLongTermProvision()).thenReturn("500");
        when(reportRequest.getCostOfProvidingServices()).thenReturn("200");
        when(reportRequest.getFinancialRevenues()).thenReturn("300");
        when(reportRequest.getNetIncomeOptionsSale()).thenReturn("150");
        when(reportRequest.getRealisedGainsOnSaleOfCryptocurrencies()).thenReturn("250");
        when(reportRequest.getStakingRewardsIncome()).thenReturn("350");
        when(reportRequest.getExtraordinaryExpenses()).thenReturn("50");
        when(reportRequest.getIncomeTaxExpense()).thenReturn("60");
        when(reportRequest.getPersonnelExpenses()).thenReturn("70");
        when(reportRequest.getGeneralAndAdministrativeExpenses()).thenReturn("80");
        when(reportRequest.getDepreciationAndImpairmentLossesOnTangibleAssets()).thenReturn("90");
        when(reportRequest.getAmortizationOnIntangibleAssets()).thenReturn("100");
        when(reportRequest.getFinancialExpenses()).thenReturn("206");
        when(reportRequest.getRentExpenses()).thenReturn("110");

        CreateReportView view = CreateReportView.fromReportRequest(reportRequest);

        assertEquals("org-123", view.getOrganisationId());
        assertTrue(view.getIncomeStatementData().isPresent());

        IncomeStatementData data = view.getIncomeStatementData().get();

        // Validate Revenues
        assertTrue(data.getRevenues().isPresent());
        assertEquals(new BigDecimal("1000"), data.getRevenues().get().getOtherIncome().get());

        // Validate Financial Income
        assertTrue(data.getFinancialIncome().isPresent());
        IncomeStatementData.FinancialIncome financialIncome = data.getFinancialIncome().get();
        assertEquals(new BigDecimal("300"), financialIncome.getFinancialRevenues().get());
        assertEquals(new BigDecimal("150"), financialIncome.getNetIncomeOptionsSale().get());
        assertEquals(new BigDecimal("250"), financialIncome.getRealisedGainsOnSaleOfCryptocurrencies().get());
        assertEquals(new BigDecimal("350"), financialIncome.getStakingRewardsIncome().get());
        assertEquals(new BigDecimal("206"), financialIncome.getFinancialExpenses().get());

        // Validate Extraordinary Income
        assertTrue(data.getExtraordinaryIncome().isPresent());
        assertEquals(new BigDecimal("50"), data.getExtraordinaryIncome().get().getExtraordinaryExpenses().get());

        // Validate Tax Expenses
        assertTrue(data.getTaxExpenses().isPresent());
        assertEquals(new BigDecimal("60"), data.getTaxExpenses().get().getIncomeTaxExpense().get());

        // Validate Operating Expenses
        assertTrue(data.getOperatingExpenses().isPresent());
        IncomeStatementData.OperatingExpenses operatingExpenses = data.getOperatingExpenses().get();
        assertEquals(new BigDecimal("70"), operatingExpenses.getPersonnelExpenses().get());
        assertEquals(new BigDecimal("80"), operatingExpenses.getGeneralAndAdministrativeExpenses().get());
        assertEquals(new BigDecimal("90"), operatingExpenses.getDepreciationAndImpairmentLossesOnTangibleAssets().get());
        assertEquals(new BigDecimal("100"), operatingExpenses.getAmortizationOnIntangibleAssets().get());
        assertEquals(new BigDecimal("110"), operatingExpenses.getRentExpenses().get());

        // Validate Additional Fields
        assertTrue(data.getRevenues().isPresent());
        assertEquals(new BigDecimal("500"), data.getRevenues().get().getBuildOfLongTermProvision().get());

        assertTrue(data.getCostOfGoodsAndServices().isPresent());
        assertEquals(new BigDecimal("200"), data.getCostOfGoodsAndServices().get().getCostOfProvidingServices().get());
    }

    @Test
    void fromReportRequest_WithUnknownReportType_ShouldHaveNoReportData() {
        when(reportRequest.getReportType()).thenReturn(null);

        CreateReportView view = CreateReportView.fromReportRequest(reportRequest);

        assertEquals("org-123", view.getOrganisationId());
        assertTrue(view.getBalanceSheetData().isEmpty());
        assertTrue(view.getIncomeStatementData().isEmpty());
    }

}
