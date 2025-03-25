package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;

import java.math.BigDecimal;
import java.util.Optional;

import lombok.Builder;
import lombok.Getter;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportRequest;

@Builder
@Getter
public class CreateReportView {

    String organisationId;
    Optional<BalanceSheetData> balanceSheetData;
    Optional<IncomeStatementData> incomeStatementData;

    public static CreateReportView fromReportRequest(ReportRequest reportRequest) {
        return CreateReportView.builder()
                .organisationId(reportRequest.getOrganisationID())
                .balanceSheetData(reportRequest.getReportType() != ReportType.BALANCE_SHEET ? Optional.empty() :
                        Optional.of(BalanceSheetData.builder()
                        .assets(BalanceSheetData.Assets.builder()
                                .nonCurrentAssets(BalanceSheetData.Assets.NonCurrentAssets.builder()
                                        .propertyPlantEquipment(new BigDecimal(reportRequest.getPropertyPlantEquipment()))
                                        .intangibleAssets(new BigDecimal(reportRequest.getIntangibleAssets()))
                                        .investments(new BigDecimal(reportRequest.getInvestments()))
                                        .financialAssets(new BigDecimal(reportRequest.getFinancialAssets()))
                                        .build())
                                .currentAssets(BalanceSheetData.Assets.CurrentAssets.builder()
                                        .prepaymentsAndOtherShortTermAssets(new BigDecimal(reportRequest.getPrepaymentsAndOtherShortTermAssets()))
                                        .otherReceivables(new BigDecimal(reportRequest.getOtherReceivables()))
                                        .cryptoAssets(new BigDecimal(reportRequest.getCryptoAssets()))
                                        .cashAndCashEquivalents(new BigDecimal(reportRequest.getCashAndCashEquivalents()))
                                        .build())
                                .build())
                        .liabilities(BalanceSheetData.Liabilities.builder()
                                .nonCurrentLiabilities(BalanceSheetData.Liabilities.NonCurrentLiabilities.builder()
                                        .provisions(new BigDecimal(reportRequest.getProvisions()))
                                        .build())
                                .currentLiabilities(BalanceSheetData.Liabilities.CurrentLiabilities.builder()
                                        .tradeAccountsPayables(new BigDecimal(reportRequest.getTradeAccountsPayables()))
                                        .otherCurrentLiabilities(new BigDecimal(reportRequest.getOtherCurrentLiabilities()))
                                        .accrualsAndShortTermProvisions(new BigDecimal(reportRequest.getAccrualsAndShortTermProvisions()))
                                        .build())
                                .build())
                        .capital(BalanceSheetData.Capital.builder()
                                .capital(new BigDecimal(reportRequest.getCapital()))
                                .profitForTheYear(new BigDecimal(reportRequest.getProfitForTheYear()))
                                .resultsCarriedForward(new BigDecimal(reportRequest.getResultsCarriedForward()))
                                .build())
                        .build()))
                .incomeStatementData(reportRequest.getReportType() != ReportType.INCOME_STATEMENT? Optional.empty() :
                        Optional.of(IncomeStatementData.builder()
                        .revenues(IncomeStatementData.Revenues.builder()
                                .otherIncome(new BigDecimal(reportRequest.getOtherIncome()))
                                .buildOfLongTermProvision(new BigDecimal(reportRequest.getBuildOfLongTermProvision()))
                                .build())
                        .costOfGoodsAndServices(IncomeStatementData.CostOfGoodsAndServices.builder()
                                .costOfProvidingServices(new BigDecimal(reportRequest.getCostOfProvidingServices()))
                                .build())
                        .financialIncome(IncomeStatementData.FinancialIncome.builder()
                                .financialRevenues(new BigDecimal(reportRequest.getFinancialRevenues()))
                                .netIncomeOptionsSale(new BigDecimal(reportRequest.getNetIncomeOptionsSale()))
                                .realisedGainsOnSaleOfCryptocurrencies(new BigDecimal(reportRequest.getRealisedGainsOnSaleOfCryptocurrencies()))
                                .stakingRewardsIncome(new BigDecimal(reportRequest.getStakingRewardsIncome()))
                                .financialExpenses(new BigDecimal(reportRequest.getFinancialExpenses()))
                                .build())
                        .extraordinaryIncome(IncomeStatementData.ExtraordinaryIncome.builder()
                                .extraordinaryExpenses(new BigDecimal(reportRequest.getExtraordinaryExpenses()))
                                .build())
                        .taxExpenses(IncomeStatementData.TaxExpenses.builder()
                                .incomeTaxExpense(new BigDecimal(reportRequest.getIncomeTaxExpense()))
                                .build())
                        .operatingExpenses(IncomeStatementData.OperatingExpenses.builder()
                                .personnelExpenses(new BigDecimal(reportRequest.getPersonnelExpenses()))
                                .generalAndAdministrativeExpenses(new BigDecimal(reportRequest.getGeneralAndAdministrativeExpenses()))
                                .depreciationAndImpairmentLossesOnTangibleAssets(new BigDecimal(reportRequest.getDepreciationAndImpairmentLossesOnTangibleAssets()))
                                .amortizationOnIntangibleAssets(new BigDecimal(reportRequest.getAmortizationOnIntangibleAssets()))
                                .rentExpenses(new BigDecimal(reportRequest.getRentExpenses()))
                                .build())
                        .build()))
                .build();
    }
}
