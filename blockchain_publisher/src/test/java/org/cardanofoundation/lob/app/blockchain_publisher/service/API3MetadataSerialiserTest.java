package org.cardanofoundation.lob.app.blockchain_publisher.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.blockchain_publisher.service.API3MetadataSerialiser.VERSION;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import lombok.val;

import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.BalanceSheetData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.IncomeStatementData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;

class API3MetadataSerialiserTest {

    private API3MetadataSerialiser serialiser;

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-06-01T10:15:30Z"), ZoneId.of("UTC"));
    private static final long CREATION_SLOT = 123456L;

    @BeforeEach
    void setUp() {
        serialiser = new API3MetadataSerialiser(FIXED_CLOCK);
    }

    @Test
    void serialiseToMetadataMap_whenBalanceSheet_shouldReturnAllFieldsCorrectly() {
        // Arrange
        val organisation = new Organisation("org-123", "Cardano Foundation", "CH", "CHE-123456", "ISO_4217:CHF");

        val balanceSheetData = BalanceSheetData.builder()
                .assets(BalanceSheetData.Assets.builder()
                        .nonCurrentAssets(BalanceSheetData.Assets.NonCurrentAssets.builder()
                                .tangibleAssets(BigDecimal.valueOf(10000))
                                .intangibleAssets(BigDecimal.valueOf(5000))
                                .investments(BigDecimal.valueOf(20000))
                                .financialAssets(BigDecimal.valueOf(30000))
                                .build())
                        .currentAssets(BalanceSheetData.Assets.CurrentAssets.builder()
                                .prepaymentsAndOtherShortTermAssets(BigDecimal.valueOf(1500))
                                .otherReceivables(BigDecimal.valueOf(2500))
                                .cryptoAssets(BigDecimal.valueOf(3500))
                                .cashAndCashEquivalents(BigDecimal.valueOf(4500))
                                .build())
                        .build())
                .liabilities(BalanceSheetData.Liabilities.builder()
                        .nonCurrentLiabilities(BalanceSheetData.Liabilities.NonCurrentLiabilities.builder()
                                .provisions(BigDecimal.valueOf(5000))
                                .build())
                        .currentLiabilities(BalanceSheetData.Liabilities.CurrentLiabilities.builder()
                                .tradeAccountsPayables(BigDecimal.valueOf(1500))
                                .otherShortTermLiabilities(BigDecimal.valueOf(2000))
                                .accrualsAndShortTermProvisions(BigDecimal.valueOf(2500))
                                .build())
                        .build())
                .capital(BalanceSheetData.Capital.builder()
                        .capital(BigDecimal.valueOf(1000))
                        .resultsCarriedForward(BigDecimal.valueOf(2000))
                        .profitForTheYear(BigDecimal.valueOf(3000))
                        .build())
                .build();

        val reportEntity = new ReportEntity();
        reportEntity.setVer(1);
        reportEntity.setType(ReportType.BALANCE_SHEET);
        reportEntity.setIntervalType(IntervalType.MONTH);
        reportEntity.setYear((short) 2024);
        reportEntity.setPeriod(Optional.of((short) 6));
        reportEntity.setMode(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportMode.USER);
        reportEntity.setOrganisation(organisation);
        reportEntity.setBalanceSheetReportData(java.util.Optional.of(balanceSheetData));

        // Act
        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(reportEntity, CREATION_SLOT);

        // Assert
        assertThat(metadataMap).isNotNull();
        assertThat(metadataMap.get("metadata")).isNotNull();

        assertThat(metadataMap.get("metadata")).isInstanceOf(MetadataMap.class);
        MetadataMap metadata = (MetadataMap) metadataMap.get("metadata");

        assertThat(metadata.get("creation_slot")).isEqualTo(BigInteger.valueOf(CREATION_SLOT));
        assertThat(metadata.get("timestamp")).isEqualTo("2024-06-01T10:15:30Z");
        assertThat(metadata.get("version")).isEqualTo(VERSION);

        assertThat(metadataMap.get("org")).isNotNull();
        assertThat(metadataMap.get("type")).isEqualTo("REPORT");
        assertThat(metadataMap.get("subType")).isEqualTo("BALANCE_SHEET");
        assertThat(metadataMap.get("interval")).isEqualTo("MONTH");
        assertThat(metadataMap.get("year")).isEqualTo("2024");
        assertThat(metadataMap.get("mode")).isEqualTo("USER");
        assertThat(metadataMap.get("ver")).isEqualTo(BigInteger.valueOf(1));
        assertThat(metadataMap.get("period")).isEqualTo(BigInteger.valueOf(6));
        assertThat(metadataMap.get("data")).isNotNull();

        // Data Section Validation
        val data = (MetadataMap) metadataMap.get("data");

        val assets = (MetadataMap) data.get("assets");
        assertThat(assets).isNotNull();

        val nonCurrentAssets = (MetadataMap) assets.get("non_current_assets");
        assertThat(nonCurrentAssets.get("tangible_assets")).isEqualTo("10000");
        assertThat(nonCurrentAssets.get("intangible_assets")).isEqualTo("5000");
        assertThat(nonCurrentAssets.get("investments")).isEqualTo("20000");
        assertThat(nonCurrentAssets.get("financial_assets")).isEqualTo("30000");

        val currentAssets = (MetadataMap) assets.get("current_assets");
        assertThat(currentAssets.get("prepayments_and_other_short_term_assets")).isEqualTo("1500");
        assertThat(currentAssets.get("other_receivables")).isEqualTo("2500");
        assertThat(currentAssets.get("crypto_assets")).isEqualTo("3500");
        assertThat(currentAssets.get("cash_and_cash_equivalents")).isEqualTo("4500");

        val liabilities = (MetadataMap) data.get("liabilities");
        val nonCurrentLiabilities = (MetadataMap) liabilities.get("non_current_liabilities");
        assertThat(nonCurrentLiabilities.get("provisions")).isEqualTo("5000");

        val currentLiabilities = (MetadataMap) liabilities.get("current_liabilities");
        assertThat(currentLiabilities.get("trade_accounts_payables")).isEqualTo("1500");
        assertThat(currentLiabilities.get("other_short_term_liabilities")).isEqualTo("2000");
        assertThat(currentLiabilities.get("accruals_and_short_term_provisions")).isEqualTo("2500");

        val capital = (MetadataMap) data.get("capital");
        assertThat(capital.get("capital")).isEqualTo("1000");
        assertThat(capital.get("results_carried_forward")).isEqualTo("2000");
        assertThat(capital.get("profit_for_the_year")).isEqualTo("3000");
    }

    @Test
    void serialiseToMetadataMap_whenIncomeStatement_shouldReturnAllFieldsCorrectly() {
        // Arrange
        val organisation = new Organisation("org-456", "Cardano Foundation", "CH", "CHE-654321", "ISO_4217:USD");

        val incomeStatementData = IncomeStatementData.builder()
                .revenues(IncomeStatementData.Revenues.builder()
                        .otherIncome(BigDecimal.valueOf(10000))
                        .buildOfLongTermProvision(BigDecimal.valueOf(5000))
                        .build())
                .costOfGoodsAndServices(IncomeStatementData.CostOfGoodsAndServices.builder()
                        .externalServices(BigDecimal.valueOf(2000))
                        .build())
                .profitForTheYear(BigDecimal.valueOf(7000))
                .build();

        val reportEntity = new ReportEntity();
        reportEntity.setVer(1);
        reportEntity.setType(ReportType.INCOME_STATEMENT);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setYear((short) 2024);
        reportEntity.setMode(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportMode.SYSTEM);
        reportEntity.setOrganisation(organisation);
        reportEntity.setIncomeStatementReportData(java.util.Optional.of(incomeStatementData));

        // Act
        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(reportEntity, CREATION_SLOT);

        // Assert
        assertThat(metadataMap).isNotNull();
        assertThat(metadataMap.get("metadata")).isNotNull();
        assertThat(metadataMap.get("org")).isNotNull();
        assertThat(metadataMap.get("type")).isEqualTo("REPORT");
        assertThat(metadataMap.get("subType")).isEqualTo("INCOME_STATEMENT");
        assertThat(metadataMap.get("interval")).isEqualTo("YEAR");
        assertThat(metadataMap.get("year")).isEqualTo("2024");
        assertThat(metadataMap.get("mode")).isEqualTo("SYSTEM");
        assertThat(metadataMap.get("ver")).isEqualTo(BigInteger.valueOf(1));
        assertThat(metadataMap.get("data")).isNotNull();


        // Data Section Validation
        val data = (MetadataMap) metadataMap.get("data");

        val revenues = (MetadataMap) data.get("revenues");
        assertThat(revenues.get("other_income")).isEqualTo("10000");
        assertThat(revenues.get("build_of_long_term_provision")).isEqualTo("5000");

        val costOfGoodsAndServices = (MetadataMap) data.get("cost_of_goods_and_services");
        assertThat(costOfGoodsAndServices.get("external_services")).isEqualTo("2000");

        assertThat(data.get("profit_for_the_year")).isEqualTo("17000");
    }

}
