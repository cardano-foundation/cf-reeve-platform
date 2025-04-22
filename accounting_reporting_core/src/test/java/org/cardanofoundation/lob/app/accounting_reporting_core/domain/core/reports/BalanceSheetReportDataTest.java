package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reports;

import java.math.BigDecimal;

import org.assertj.core.api.Assertions;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData;

class BalanceSheetReportDataTest {

    @Test
    void testIsValid_Report_PositiveScenario() {
        // Assets = Liabilities + Capital
        BalanceSheetData reportData = BalanceSheetData.builder()
                .assets(BalanceSheetData.Assets.builder()
                        .nonCurrentAssets(BalanceSheetData.Assets.NonCurrentAssets.builder()
                                .tangibleAssets(new BigDecimal("500"))
                                .intangibleAssets(new BigDecimal("200"))
                                .build())
                        .currentAssets(BalanceSheetData.Assets.CurrentAssets.builder()
                                .cashAndCashEquivalents(new BigDecimal("300"))
                                .build())
                        .build())
                .liabilities(BalanceSheetData.Liabilities.builder()
                        .nonCurrentLiabilities(BalanceSheetData.Liabilities.NonCurrentLiabilities.builder()
                                .provisions(new BigDecimal("500"))
                                .build())
                        .currentLiabilities(BalanceSheetData.Liabilities.CurrentLiabilities.builder()
                                .tradeAccountsPayables(new BigDecimal("200"))
                                .build())
                        .build())
                .capital(BalanceSheetData.Capital.builder()
                        .capital(new BigDecimal("300"))
                        .build())
                .build();

        Assertions.assertThat(reportData.isValid()).isTrue();
    }

    @Test
    void testIsValid_Report_NegativeScenario_AssetsNotEqualToLiabilitiesPlusCapital() {
        BalanceSheetData reportData = BalanceSheetData.builder()
                .assets(BalanceSheetData.Assets.builder()
                        .nonCurrentAssets(BalanceSheetData.Assets.NonCurrentAssets.builder()
                                .tangibleAssets(new BigDecimal("1000"))
                                .build())
                        .build())
                .liabilities(BalanceSheetData.Liabilities.builder()
                        .nonCurrentLiabilities(BalanceSheetData.Liabilities.NonCurrentLiabilities.builder()
                                .provisions(new BigDecimal("300"))
                                .build())
                        .build())
                .capital(BalanceSheetData.Capital.builder()
                        .capital(new BigDecimal("200"))
                        .build())
                .build();

        Assertions.assertThat(reportData.isValid()).isFalse();
    }

    @Test
    void testIsValid_Report_NegativeScenario_NullAssets() {
        BalanceSheetData reportData = BalanceSheetData.builder()
                .assets(null)
                .liabilities(BalanceSheetData.Liabilities.builder()
                        .nonCurrentLiabilities(BalanceSheetData.Liabilities.NonCurrentLiabilities.builder()
                                .provisions(new BigDecimal("500"))
                                .build())
                        .build())
                .capital(BalanceSheetData.Capital.builder()
                        .capital(new BigDecimal("300"))
                        .build())
                .build();

        Assertions.assertThat(reportData.isValid()).isFalse();
    }

    @Test
    void testIsValid_Report_NegativeScenario_NullLiabilities() {
        BalanceSheetData reportData = BalanceSheetData.builder()
                .assets(BalanceSheetData.Assets.builder()
                        .nonCurrentAssets(BalanceSheetData.Assets.NonCurrentAssets.builder()
                                .tangibleAssets(new BigDecimal("500"))
                                .build())
                        .build())
                .liabilities(null)
                .capital(BalanceSheetData.Capital.builder()
                        .capital(new BigDecimal("500"))
                        .build())
                .build();

        Assertions.assertThat(reportData.isValid()).isFalse();
    }

    @Test
    void testIsValid_Report_NegativeScenario_NullCapital() {
        BalanceSheetData reportData = BalanceSheetData.builder()
                .assets(BalanceSheetData.Assets.builder()
                        .nonCurrentAssets(BalanceSheetData.Assets.NonCurrentAssets.builder()
                                .tangibleAssets(new BigDecimal("500"))
                                .build())
                        .build())
                .liabilities(BalanceSheetData.Liabilities.builder()
                        .nonCurrentLiabilities(BalanceSheetData.Liabilities.NonCurrentLiabilities.builder()
                                .provisions(new BigDecimal("300"))
                                .build())
                        .build())
                .capital(null)
                .build();

        Assertions.assertThat(reportData.isValid()).isFalse();
    }

}
