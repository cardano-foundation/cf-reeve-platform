package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.metrics.executors;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.metric.IncomeStatemenCategories;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.metric.MetricEnum;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.ReportRepository;

@ExtendWith(MockitoExtension.class)
class IncomeStatementMetricServiceTest {

    @Mock
    private ReportRepository reportRepository;
    private IncomeStatementMetricService incomeStatementMetricService;

    @BeforeEach
    void setup() {
        incomeStatementMetricService = new IncomeStatementMetricService(reportRepository);
        incomeStatementMetricService.init();
    }

    @Test
    void getProfitOfTheYearTest() {
        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setType(ReportType.INCOME_STATEMENT);
        reportEntity.setYear((short) 2024);
        reportEntity.setIncomeStatementReportData(Optional.of(getTestIncomeStatementData()));

        when(reportRepository.getNewestReportsInRange(anyString(), any(), any()))
                .thenReturn(List.of(reportEntity));

        Map<Short, Long> profitOfTheYear = (Map<Short, Long>) incomeStatementMetricService.getData(MetricEnum.SubMetric.PROFIT_OF_THE_YEAR, "organisationId", Optional.empty(), Optional.empty());

        assertThat(profitOfTheYear).isNotNull();
        assertThat(profitOfTheYear).containsEntry((short) 2024, 150L);
    }

    @Test
    void getTotalExpensesTest() {
        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setType(ReportType.INCOME_STATEMENT);
        reportEntity.setIncomeStatementReportData(Optional.of(getTestIncomeStatementData()));

        when(reportRepository.getNewestReportsInRange(anyString(), any(), any()))
                .thenReturn(List.of(reportEntity));

        Map<IncomeStatemenCategories, Integer> totalExpenses = (Map<IncomeStatemenCategories, Integer>) incomeStatementMetricService.getData(MetricEnum.SubMetric.TOTAL_EXPENSES, "organisationId", Optional.empty(), Optional.empty());

        assertThat(totalExpenses).isNotNull();
        assertThat(totalExpenses).containsEntry(IncomeStatemenCategories.PERSONNEL_EXPENSES, 10);
        assertThat(totalExpenses).containsEntry(IncomeStatemenCategories.COST_OF_SERVICE, 10);
        assertThat(totalExpenses).containsEntry(IncomeStatemenCategories.FINANCIAL_EXPENSES, 10);
        assertThat(totalExpenses).containsEntry(IncomeStatemenCategories.TAX_EXPENSES, 10);
        assertThat(totalExpenses).containsEntry(IncomeStatemenCategories.OTHER_OPERATING_EXPENSES, 50);
    }

    @Test
    void getIncomeStreamTest() {
        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setType(ReportType.INCOME_STATEMENT);
        reportEntity.setIncomeStatementReportData(Optional.of(getTestIncomeStatementData()));

        when(reportRepository.getNewestReportsInRange(anyString(), any(), any()))
                .thenReturn(List.of(reportEntity));

        Map<IncomeStatemenCategories, Integer> incomeStream = (Map<IncomeStatemenCategories, Integer>) incomeStatementMetricService.getData(MetricEnum.SubMetric.INCOME_STREAMS, "organisationId", Optional.empty(), Optional.empty());

        assertThat(incomeStream).isNotNull();
        assertThat(incomeStream).containsEntry(IncomeStatemenCategories.BUILDING_OF_PROVISIONS,10);
        assertThat(incomeStream).containsEntry(IncomeStatemenCategories.STAKING_REWARDS,10);
        assertThat(incomeStream).containsEntry(IncomeStatemenCategories.OTHER,20);
        assertThat(incomeStream).containsEntry(IncomeStatemenCategories.GAINS_ON_SALES_OF_CRYPTO_CURRENCIES, 10);
        assertThat(incomeStream).containsEntry(IncomeStatemenCategories.FINANCIAL_INCOME, 10);
    }

    private IncomeStatementData getTestIncomeStatementData() {
        return IncomeStatementData.builder()
                .costOfGoodsAndServices(IncomeStatementData.CostOfGoodsAndServices.builder()
                        .externalServices(BigDecimal.TEN)
                        .build())
                .operatingExpenses(IncomeStatementData.OperatingExpenses.builder()
                        .personnelExpenses(BigDecimal.TEN)
                        .amortizationOnIntangibleAssets(BigDecimal.TEN) // Expenses -  what's with this one?
                        .depreciationAndImpairmentLossesOnTangibleAssets(BigDecimal.TEN) // Expenses - what's with this one?
                        .rentExpenses(BigDecimal.TEN) // Expenses - what's with this one?
                        .generalAndAdministrativeExpenses(BigDecimal.TEN) // Expenses - what's with this one?
                        .build())
                .financialIncome(IncomeStatementData.FinancialIncome.builder()
                        .financialExpenses(BigDecimal.TEN)
                        .stakingRewardsIncome(BigDecimal.TEN)
                        .netIncomeOptionsSale(BigDecimal.TEN) // INCOME - what's with this one?
                        .financialRevenues(BigDecimal.TEN) // INCOME - what's with this one?
                        .realisedGainsOnSaleOfCryptocurrencies(BigDecimal.TEN) // INCOME - what's with this one?
                        .build())
                .taxExpenses(IncomeStatementData.TaxExpenses.builder()
                        .directTaxes(BigDecimal.TEN)
                        .build())
                .extraordinaryIncome(IncomeStatementData.ExtraordinaryIncome.builder()
                        .extraordinaryExpenses(BigDecimal.TEN) // Expenses - what's with this one?
                        .build())
                .profitForTheYear(BigDecimal.TEN)
                .revenues(IncomeStatementData.Revenues.builder()
                        .buildOfLongTermProvision(BigDecimal.TEN) // INCOME - what's with this one?
                        .otherIncome(BigDecimal.TEN) // INCOME - what's with this one?
                        .build())
                .build();
    }

}
