package org.cardanofoundation.lob.app.blockchain_publisher.service;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType.BALANCE_SHEET;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType.INCOME_STATEMENT;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.Report;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.BalanceSheetData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.IncomeStatementData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReportConverter {

    private final BlockchainPublishStatusMapper blockchainPublishStatusMapper;

    public ReportEntity convertToDbDetached(Report report) {
        val reportEntity = new ReportEntity();
        reportEntity.setReportId(report.getReportId());
        reportEntity.setType(report.getType());
        reportEntity.setDate(report.getDate());
        reportEntity.setIntervalType(report.getIntervalType());
        reportEntity.setMode(report.getMode());
        reportEntity.setPeriod(report.getPeriod());
        reportEntity.setYear(report.getYear());
        reportEntity.setVer(report.getVer());

        if (report.getType() == INCOME_STATEMENT) {
            reportEntity.setIncomeStatementReportData(report.getIncomeStatementData().map(ReportConverter::convertIncomeStatementData));
        }
        if (report.getType() == BALANCE_SHEET) {
            reportEntity.setBalanceSheetReportData(report.getBalanceSheetData().map(ReportConverter::convertBalanceSheetData));
        }
        reportEntity.setOrganisation(convertOrganisation(report.getOrganisation()));

        val publishStatus = blockchainPublishStatusMapper.convert(report.getLedgerDispatchStatus());
        reportEntity.setL1SubmissionData(Optional.of(L1SubmissionData.builder()
                .publishStatus(publishStatus)
                .build())
        );

        return reportEntity;
    }

    private static Organisation convertOrganisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Organisation org) {
        return Organisation.builder()
                .id(org.getId())
                .name(org.getName().orElseThrow())
                .countryCode(org.getCountryCode().orElseThrow())
                .taxIdNumber(org.getTaxIdNumber().orElseThrow())
                .currencyId(org.getCurrencyId())
                .build();
    }

    private static IncomeStatementData convertIncomeStatementData(
            org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IncomeStatementData incomeStatementData) {
        return IncomeStatementData.builder()
                .revenues(incomeStatementData.getRevenues()
                        .map(rev -> IncomeStatementData.Revenues.builder()
                                .otherIncome(rev.getOtherIncome().orElse(null))
                                .buildOfLongTermProvision(rev.getBuildOfLongTermProvision().orElse(null))
                                .build())
                        .orElse(null))
                .costOfGoodsAndServices(incomeStatementData.getCostOfServicesAndGoods()
                        .map(cost -> IncomeStatementData.CostOfGoodsAndServices.builder()
                                .externalServices(cost.getCostOfProvidingServices().orElse(null))
                                .build())
                        .orElse(null))
                .operatingExpenses(incomeStatementData.getOperatingExpenses()
                        .map(ops -> IncomeStatementData.OperatingExpenses.builder()
                                .personnelExpenses(ops.getPersonnelExpenses().orElse(null))
                                .generalAndAdministrativeExpenses(ops.getGeneralAndAdministrativeExpenses().orElse(null))
                                .depreciationAndImpairmentLossesOnTangibleAssets(ops.getDepreciationAndImpairmentLossesOnTangibleAssets().orElse(null))
                                .amortizationOnIntangibleAssets(ops.getAmortizationOnIntangibleAssets().orElse(null))
                                .rentExpenses(ops.getRentExpenses().orElse(null))
                                .build())
                        .orElse(null))
                .financialIncome(incomeStatementData.getFinancialIncome()
                        .map(fin -> IncomeStatementData.FinancialIncome.builder()
                                .financialRevenues(fin.getFinancialRevenues().orElse(null))
                                .financialExpenses(fin.getFinancialExpenses().orElse(null))
                                .realisedGainsOnSaleOfCryptocurrencies(fin.getRealisedGainsOnSaleOfCryptocurrencies().orElse(null))
                                .stakingRewardsIncome(fin.getStakingRewardsIncome().orElse(null))
                                .netIncomeOptionsSale(fin.getNetIncomeOptionsSale().orElse(null))
                                .build())
                        .orElse(null))
                .extraordinaryIncome(incomeStatementData.getExtraordinaryIncome()
                        .map(ext -> IncomeStatementData.ExtraordinaryIncome.builder()
                                .extraordinaryExpenses(ext.getExtraordinaryExpenses().orElse(null))
                                .build())
                        .orElse(null))
                .taxExpenses(incomeStatementData.getTaxExpenses()
                        .map(tax -> IncomeStatementData.TaxExpenses.builder()
                                .directTaxes(tax.getIncomeTaxExpense().orElse(null))
                                .build())
                        .orElse(null))
                .profitForTheYear(incomeStatementData.getProfitForTheYear().orElse(null))
                .build();
    }

    private static BalanceSheetData convertBalanceSheetData(
            org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.BalanceSheetData balanceSheetData) {
        return BalanceSheetData.builder()
                .assets(balanceSheetData.getAssets()
                        .map(assets -> BalanceSheetData.Assets.builder()
                                .nonCurrentAssets(assets.getNonCurrentAssets()
                                        .map(ncAssets -> BalanceSheetData.Assets.NonCurrentAssets.builder()
                                                .tangibleAssets(ncAssets.getPropertyPlantEquipment().orElse(null))
                                                .intangibleAssets(ncAssets.getIntangibleAssets().orElse(null))
                                                .investments(ncAssets.getInvestments().orElse(null))
                                                .financialAssets(ncAssets.getFinancialAssets().orElse(null))
                                                .build())
                                        .orElse(null))
                                .currentAssets(assets.getCurrentAssets()
                                        .map(cAssets -> BalanceSheetData.Assets.CurrentAssets.builder()
                                                .prepaymentsAndOtherShortTermAssets(cAssets.getPrepaymentsAndOtherShortTermAssets().orElse(null))
                                                .otherReceivables(cAssets.getOtherReceivables().orElse(null))
                                                .cryptoAssets(cAssets.getCryptoAssets().orElse(null))
                                                .cashAndCashEquivalents(cAssets.getCashAndCashEquivalents().orElse(null))
                                                .build())
                                        .orElse(null))
                                .build())
                        .orElse(null))
                .liabilities(balanceSheetData.getLiabilities()
                        .map(liabilities -> BalanceSheetData.Liabilities.builder()
                                .nonCurrentLiabilities(liabilities.getNonCurrentLiabilities()
                                        .map(ncLiabilities -> BalanceSheetData.Liabilities.NonCurrentLiabilities.builder()
                                                .provisions(ncLiabilities.getProvisions().orElse(null))
                                                .build())
                                        .orElse(null))
                                .currentLiabilities(liabilities.getCurrentLiabilities()
                                        .map(cLiabilities -> BalanceSheetData.Liabilities.CurrentLiabilities.builder()
                                                .tradeAccountsPayables(cLiabilities.getTradeAccountsPayables().orElse(null))
                                                .otherShortTermLiabilities(cLiabilities.getOtherCurrentLiabilities().orElse(null))
                                                .accrualsAndShortTermProvisions(cLiabilities.getAccrualsAndShortTermProvisions().orElse(null))
                                                .build())
                                        .orElse(null))
                                .build())
                        .orElse(null))
                .capital(balanceSheetData.getCapital()
                        .map(capital -> BalanceSheetData.Capital.builder()
                                .capital(capital.getCapital().orElse(null))
                                .profitForTheYear(capital.getProfitForTheYear().orElse(null))
                                .resultsCarriedForward(capital.getResultsCarriedForward().orElse(null))
                                .build())
                        .orElse(null))
                .build();
    }

}
