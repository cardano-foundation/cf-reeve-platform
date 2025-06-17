package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.metrics.executors;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.metric.MetricEnum.BALANCE_SHEET;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.metric.BalanceSheetCategories;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.metric.MetricEnum;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.ReportService;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.metrics.MetricExecutor;

@Component
@RequiredArgsConstructor
public class BalanceSheetMetricService extends MetricExecutor {

    private final ReportService reportService;

    @PostConstruct
    public void init() {
        name = BALANCE_SHEET;
        metrics = Map.of(
                MetricEnum.SubMetric.ASSET_CATEGORIES, this::getAssetCategories,
                MetricEnum.SubMetric.BALANCE_SHEET_OVERVIEW, this::getBalanceSheetOverview,
                MetricEnum.SubMetric.TOTAL_ASSETS, this::getTotalAssets,
                MetricEnum.SubMetric.TOTAL_LIABILITIES, this::getTotalLiabilities
        );
    }

    private Object getTotalLiabilities(String organisationID, Optional<LocalDate> startDate, Optional<LocalDate> endDate) {
        Set<ReportEntity> reportEntities = reportService.findReportsInDateRange(organisationID, ReportType.BALANCE_SHEET, startDate, endDate);

        final BigDecimal[] totalLiabilities = {BigDecimal.ZERO};
        Optional<ReportEntity> maxEntityO = reportService.getMostRecentReport(reportEntities);
        if(maxEntityO.isEmpty()) {
            return totalLiabilities[0];
        }
        maxEntityO.get().getBalanceSheetReportData().flatMap(BalanceSheetData::getLiabilities).ifPresent(liabilities -> {
            liabilities.getCurrentLiabilities().ifPresent(currentLiabilities -> {
                currentLiabilities.getTradeAccountsPayables().ifPresent(tradeAccountsPayables -> totalLiabilities[0] = totalLiabilities[0].add(tradeAccountsPayables));
                currentLiabilities.getOtherShortTermLiabilities().ifPresent(otherCurrentLiabilities -> totalLiabilities[0] = totalLiabilities[0].add(otherCurrentLiabilities));
                currentLiabilities.getAccrualsAndShortTermProvisions().ifPresent(accruals -> totalLiabilities[0] = totalLiabilities[0].add(accruals));
            });
            liabilities.getNonCurrentLiabilities().flatMap(BalanceSheetData.Liabilities.NonCurrentLiabilities::getProvisions).ifPresent(provisions -> totalLiabilities[0] = totalLiabilities[0].add(provisions));
        });
        return totalLiabilities[0];
    }

    private Object getTotalAssets(String organisationID, Optional<LocalDate> startDate, Optional<LocalDate> endDate) {
        Set<ReportEntity> reportEntities = reportService.findReportsInDateRange(organisationID, ReportType.BALANCE_SHEET, startDate, endDate);

        final BigDecimal[] totalAssets = {BigDecimal.ZERO};
        Optional<ReportEntity> maxEntityO = reportService.getMostRecentReport(reportEntities);
        if(maxEntityO.isEmpty()) {
            return totalAssets;
        }
        maxEntityO.get().getBalanceSheetReportData().flatMap(BalanceSheetData::getAssets).ifPresent(assets -> {
            assets.getCurrentAssets().ifPresent(currentAssets -> {
                currentAssets.getCryptoAssets().ifPresent(cryptoAssets -> totalAssets[0] = totalAssets[0].add(cryptoAssets));
                currentAssets.getCashAndCashEquivalents().ifPresent(cash -> totalAssets[0] = totalAssets[0].add(cash));
                currentAssets.getOtherReceivables().ifPresent(otherReceivables -> totalAssets[0] = totalAssets[0].add(otherReceivables));
                currentAssets.getPrepaymentsAndOtherShortTermAssets().ifPresent(prepayments -> totalAssets[0] = totalAssets[0].add(prepayments));
            });
            assets.getNonCurrentAssets().ifPresent(nonCurrentAssets -> {
                nonCurrentAssets.getFinancialAssets().ifPresent(financialAssets -> totalAssets[0] = totalAssets[0].add(financialAssets));
                nonCurrentAssets.getIntangibleAssets().ifPresent(intangibleAssets -> totalAssets[0] = totalAssets[0].add(intangibleAssets));
                nonCurrentAssets.getTangibleAssets().ifPresent(propertyPlantEquipment -> totalAssets[0] = totalAssets[0].add(propertyPlantEquipment));
                nonCurrentAssets.getInvestments().ifPresent(investments -> totalAssets[0] = totalAssets[0].add(investments));
            });
        });
        return totalAssets[0];
    }

    private Map<BalanceSheetCategories, Integer> getAssetCategories(String organisationID, Optional<LocalDate> startDate, Optional<LocalDate> endDate) {
        Set<ReportEntity> reportEntities = reportService.findReportsInDateRange(organisationID, ReportType.BALANCE_SHEET, startDate, endDate);

        Map<BalanceSheetCategories, Integer> assetCategories = new EnumMap<>(BalanceSheetCategories.class);

        Optional<ReportEntity> maxEntityO = reportService.getMostRecentReport(reportEntities);
        if(maxEntityO.isEmpty()) {
            return assetCategories;
        }
        maxEntityO.get().getBalanceSheetReportData().flatMap(BalanceSheetData::getAssets).ifPresent(assets -> {
            assets.getCurrentAssets().ifPresent(currentAssets -> {
                currentAssets.getCashAndCashEquivalents().ifPresent(
                        cash -> assetCategories.merge(BalanceSheetCategories.CASH, cash.intValue(), Integer::sum));
                currentAssets.getCryptoAssets().ifPresent(
                        cryptoAssets -> assetCategories.merge(BalanceSheetCategories.CRYPTO_ASSETS, cryptoAssets.intValue(), Integer::sum));
                currentAssets.getOtherReceivables().ifPresent(
                        otherReceivables -> assetCategories.merge(BalanceSheetCategories.OTHER, otherReceivables.intValue(), Integer::sum));
                currentAssets.getPrepaymentsAndOtherShortTermAssets().ifPresent(
                        prepayments -> assetCategories.merge(BalanceSheetCategories.OTHER, prepayments.intValue(), Integer::sum));
            });

            assets.getNonCurrentAssets().ifPresent(nonCurrentAssets -> {
                nonCurrentAssets.getFinancialAssets().ifPresent(
                        financialAssets -> assetCategories.merge(BalanceSheetCategories.FINANCIAL_ASSETS, financialAssets.intValue(), Integer::sum));
                nonCurrentAssets.getIntangibleAssets().ifPresent(
                        intangibleAssets -> assetCategories.merge(BalanceSheetCategories.OTHER, intangibleAssets.intValue(), Integer::sum));
                nonCurrentAssets.getInvestments().ifPresent(
                        investments -> assetCategories.merge(BalanceSheetCategories.OTHER, investments.intValue(), Integer::sum));
                nonCurrentAssets.getTangibleAssets().ifPresent(
                        propertyPlantEquipment -> assetCategories.merge(BalanceSheetCategories.OTHER, propertyPlantEquipment.intValue(), Integer::sum));
            });
        });

        return assetCategories;
    }

    private Map<BalanceSheetCategories, Map<BalanceSheetCategories, Integer>> getBalanceSheetOverview(String organisationID, Optional<LocalDate> startDate, Optional<LocalDate> endDate) {
        Set<ReportEntity> reportEntities = reportService.findReportsInDateRange(organisationID, ReportType.BALANCE_SHEET, startDate, endDate);

        Map<BalanceSheetCategories, Map<BalanceSheetCategories, Integer>> balanceSheetOverview = new EnumMap<>(BalanceSheetCategories.class);

        Optional<ReportEntity> maxEntityO = reportService.getMostRecentReport(reportEntities);
        if(maxEntityO.isEmpty()) {
            return balanceSheetOverview;
        }
        maxEntityO.get().getBalanceSheetReportData().ifPresent(balanceSheetData -> {
            balanceSheetData.getAssets().ifPresent(assets -> {
                Map<BalanceSheetCategories, Integer> assetMap = balanceSheetOverview.getOrDefault(BalanceSheetCategories.ASSETS, new HashMap<>());
                processAssets(assets, assetMap);
                balanceSheetOverview.put(BalanceSheetCategories.ASSETS, assetMap);
            });

            balanceSheetData.getLiabilities().ifPresent(liabilities -> {
                Map<BalanceSheetCategories, Integer> liabilityMap = balanceSheetOverview.getOrDefault(BalanceSheetCategories.LIABILITIES, new HashMap<>());
                processLiabilities(liabilities, liabilityMap);
                balanceSheetOverview.put(BalanceSheetCategories.LIABILITIES, liabilityMap);
            });
            balanceSheetData.getCapital().ifPresent(capital -> {
                Map<BalanceSheetCategories, Integer> liabilityMap = balanceSheetOverview.getOrDefault(BalanceSheetCategories.LIABILITIES, new HashMap<>());
                processCapital(capital, liabilityMap);
                balanceSheetOverview.put(BalanceSheetCategories.LIABILITIES, liabilityMap);
            });
        });
        return balanceSheetOverview;
    }

    private static void processCapital(BalanceSheetData.Capital capital, Map<BalanceSheetCategories, Integer> liabilityMap) {
        capital.getCapital().ifPresent(
                capitalValue -> liabilityMap.merge(BalanceSheetCategories.CAPITAL, capitalValue.intValue(), Integer::sum));
        capital.getProfitForTheYear().ifPresent(
                profitForTheYear -> liabilityMap.merge(BalanceSheetCategories.PROFIT_OF_THE_YEAR, profitForTheYear.intValue(), Integer::sum));
        capital.getResultsCarriedForward().ifPresent(
                resultsCarriedForward -> liabilityMap.merge(BalanceSheetCategories.RESULTS_CARRIED_FORWARD, resultsCarriedForward.intValue(), Integer::sum));
    }

    private static void processLiabilities(BalanceSheetData.Liabilities liabilities, Map<BalanceSheetCategories, Integer> liabilityMap) {
        liabilities.getCurrentLiabilities().ifPresent(currentLiabilities -> {
            currentLiabilities.getAccrualsAndShortTermProvisions().ifPresent(
                    accruals -> liabilityMap.merge(BalanceSheetCategories.ACCRUSAL_AND_SHORT_TERM_PROVISIONS, accruals.intValue(), Integer::sum));
            currentLiabilities.getTradeAccountsPayables().ifPresent(
                    tradeAccountsPayables -> liabilityMap.merge(BalanceSheetCategories.TRADE_ACCOUNTS_PAYABLE, tradeAccountsPayables.intValue(), Integer::sum));
            currentLiabilities.getOtherShortTermLiabilities().ifPresent(
                    otherCurrentLiabilities -> liabilityMap.merge(BalanceSheetCategories.OTHER, otherCurrentLiabilities.intValue(), Integer::sum));
        });
        liabilities.getNonCurrentLiabilities().flatMap(BalanceSheetData.Liabilities.NonCurrentLiabilities::getProvisions).ifPresent(
                provisions -> liabilityMap.merge(BalanceSheetCategories.PROVISIONS, provisions.intValue(), Integer::sum));
    }

    private static void processAssets(BalanceSheetData.Assets assets, Map<BalanceSheetCategories, Integer> assetMap) {
        assets.getCurrentAssets().ifPresent(currentAssets -> {
            currentAssets.getCryptoAssets().ifPresent(
                    cryptoAssets -> assetMap.merge(BalanceSheetCategories.CRYPTO_ASSETS, cryptoAssets.intValue(), Integer::sum));
            currentAssets.getCashAndCashEquivalents().ifPresent(
                    cash -> assetMap.merge(BalanceSheetCategories.CASH, cash.intValue(), Integer::sum));
            currentAssets.getOtherReceivables().ifPresent(
                    otherReceivables -> assetMap.merge(BalanceSheetCategories.OTHER, otherReceivables.intValue(), Integer::sum));
            currentAssets.getPrepaymentsAndOtherShortTermAssets().ifPresent(
                    prepayments -> assetMap.merge(BalanceSheetCategories.PREPAYMENTS, prepayments.intValue(), Integer::sum));
        });
        assets.getNonCurrentAssets().ifPresent(nonCurrentAssets -> {
            nonCurrentAssets.getFinancialAssets().ifPresent(
                    financialAssets -> assetMap.merge(BalanceSheetCategories.FINANCIAL_ASSETS, financialAssets.intValue(), Integer::sum));

            nonCurrentAssets.getIntangibleAssets().ifPresent(
                    intangibleAssets -> assetMap.merge(BalanceSheetCategories.INTANGIBLE_ASSETS, intangibleAssets.intValue(), Integer::sum));

            nonCurrentAssets.getInvestments().ifPresent(
                    investments -> assetMap.merge(BalanceSheetCategories.INVESTMENTS, investments.intValue(), Integer::sum));

            nonCurrentAssets.getTangibleAssets().ifPresent(
                    propertyPlantEquipment -> assetMap.merge(BalanceSheetCategories.PROPERTY_PLANT_EQUIPMENT, propertyPlantEquipment.intValue(), Integer::sum));
        });
    }
}
