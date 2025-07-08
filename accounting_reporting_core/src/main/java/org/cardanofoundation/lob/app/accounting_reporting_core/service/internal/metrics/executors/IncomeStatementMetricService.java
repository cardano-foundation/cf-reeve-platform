package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.metrics.executors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.metric.IncomeStatemenCategories;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.metric.MetricEnum;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.ReportService;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.metrics.MetricExecutor;

@Component
@RequiredArgsConstructor
public class IncomeStatementMetricService extends MetricExecutor {

    private final ReportService reportService;

    @PostConstruct
    public void init() {
        name = MetricEnum.INCOME_STATEMENT;
        metrics = Map.of(
                MetricEnum.SubMetric.TOTAL_EXPENSES, this::getTotalExpenses,
                MetricEnum.SubMetric.INCOME_STREAMS, this::getIncomeStream,
                MetricEnum.SubMetric.PROFIT_OF_THE_YEAR, this::getProfitOfTheYear
        );
    }

    private Map<Short, Long> getProfitOfTheYear(String organisationID, Optional<LocalDate> startDate, Optional<LocalDate> endDate) {
        Set<ReportEntity> reportEntities = reportService.findReportsInDateRange(organisationID, ReportType.INCOME_STATEMENT, startDate, endDate);
        return reportEntities.stream()
                .collect(Collectors.groupingBy(
                        ReportEntity::getYear,
                        Collectors.collectingAndThen(
                                Collectors.maxBy(Comparator.comparing(o ->
                                        reportService.getReportEndDate(
                                                o.getIntervalType(),
                                                reportService.getReportStartDate(
                                                        o.getIntervalType(),
                                                        o.getPeriod().orElseThrow(), // handle Optional safely if needed
                                                        o.getYear()
                                                )
                                        )
                                )),
                                maxReportOpt -> maxReportOpt
                                        .flatMap(ReportEntity::getIncomeStatementReportData)
                                        .flatMap(data -> data.getProfitForTheYear().map(BigDecimal::longValue))
                                        .orElse(0L)
                        )
                ));
    }

    private Map<IncomeStatemenCategories, Integer> getTotalExpenses(String organisationID, Optional<LocalDate> startDate, Optional<LocalDate> endDate) {
        Set<ReportEntity> reportEntities = reportService.findReportsInDateRange(organisationID, ReportType.INCOME_STATEMENT, startDate, endDate);

        Map<IncomeStatemenCategories, Integer> totalExpenses = new EnumMap<>(IncomeStatemenCategories.class);
        Optional<ReportEntity> maxEntityO = reportService.getMostRecentReport(reportEntities);
        if(maxEntityO.isEmpty()) {
            return totalExpenses;
        }
        ReportEntity maxEntity = maxEntityO.get();
        if(maxEntity.getIncomeStatementReportData().isPresent()) {
            IncomeStatementData incomeStatementData = maxEntity.getIncomeStatementReportData().get();
            incomeStatementData.getCostOfGoodsAndServices().ifPresent(costOfGoodsAndServices -> totalExpenses.merge(IncomeStatemenCategories.COST_OF_SERVICE, costOfGoodsAndServices.getExternalServices().orElse(BigDecimal.ZERO).intValue(), Integer::sum));
            incomeStatementData.getOperatingExpenses().ifPresent(operatingExpenses -> {
                totalExpenses.merge(IncomeStatemenCategories.PERSONNEL_EXPENSES, operatingExpenses.getPersonnelExpenses().orElse(BigDecimal.ZERO).intValue(), Integer::sum);
                // Financial Expenses Expenses
                int financialExpenses = sumUpOptionalFields(
                        operatingExpenses.getGeneralAndAdministrativeExpenses(),
                        operatingExpenses.getAmortizationOnIntangibleAssets(),
                        operatingExpenses.getDepreciationAndImpairmentLossesOnTangibleAssets(),
                        operatingExpenses.getRentExpenses());
                totalExpenses.merge(IncomeStatemenCategories.FINANCIAL_EXPENSES, financialExpenses, Integer::sum);
            });
            incomeStatementData.getFinancialIncome().ifPresent(financialIncome -> totalExpenses.merge(IncomeStatemenCategories.TAX_EXPENSES, financialIncome.getFinancialExpenses().orElse(BigDecimal.ZERO).intValue(), Integer::sum));
            incomeStatementData.getTaxExpenses().ifPresent(taxExpenses -> totalExpenses.merge(IncomeStatemenCategories.OTHER_OPERATING_EXPENSES, taxExpenses.getDirectTaxes().orElse(BigDecimal.ZERO).intValue(), Integer::sum));
        }
        return totalExpenses;
    }

    private Map<IncomeStatemenCategories, Integer> getIncomeStream(String organisationID, Optional<LocalDate> startDate, Optional<LocalDate> endDate) {
        Set<ReportEntity> reportEntities = reportService.findReportsInDateRange(organisationID, ReportType.INCOME_STATEMENT, startDate, endDate);

        Map<IncomeStatemenCategories, Integer> incomeStream = new EnumMap<>(IncomeStatemenCategories.class);
        Optional<ReportEntity> maxEntityO = reportService.getMostRecentReport(reportEntities);
        if(maxEntityO.isEmpty()) {
            return incomeStream;
        }
        ReportEntity maxEntity = maxEntityO.get();

        maxEntity.getIncomeStatementReportData().ifPresent(incomeStatementData -> {
            incomeStatementData.getFinancialIncome().ifPresent(financialIncome -> {
                incomeStream.merge(IncomeStatemenCategories.STAKING_REWARDS, financialIncome.getStakingRewardsIncome().orElse(BigDecimal.ZERO).intValue(), Integer::sum);
                incomeStream.merge(IncomeStatemenCategories.OTHER, financialIncome.getNetIncomeOptionsSale().orElse(BigDecimal.ZERO).intValue(), Integer::sum);
                incomeStream.merge(IncomeStatemenCategories.FINANCIAL_INCOME, financialIncome.getFinancialRevenues().orElse(BigDecimal.ZERO).intValue(), Integer::sum);
                incomeStream.merge(IncomeStatemenCategories.GAINS_ON_SALES_OF_CRYPTO_CURRENCIES, financialIncome.getRealisedGainsOnSaleOfCryptocurrencies().orElse(BigDecimal.ZERO).intValue(), Integer::sum);
            });
            incomeStatementData.getRevenues().ifPresent(revenues -> {
                incomeStream.merge(IncomeStatemenCategories.BUILDING_OF_PROVISIONS, revenues.getBuildOfLongTermProvision().orElse(BigDecimal.ZERO).intValue(), Integer::sum);
                incomeStream.merge(IncomeStatemenCategories.OTHER, revenues.getOtherIncome().orElse(BigDecimal.ZERO).intValue(), Integer::sum);
            });
        });

        return incomeStream;
    }

    @SafeVarargs
    private int sumUpOptionalFields(Optional<BigDecimal>... fields) {
        return Stream.of(fields)
                .map(field -> field.orElse(BigDecimal.ZERO))
                .map(BigDecimal::intValue)
                .reduce(Integer::sum)
                .orElse(0);
    }

}
