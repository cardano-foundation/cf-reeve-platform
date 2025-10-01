package org.cardanofoundation.lob.app.blockchain_publisher.service;


import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import co.nstant.in.cbor.CborException;
import com.bloxbean.cardano.client.crypto.bip39.Sha256Hash;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.util.HexUtil;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.BalanceSheetData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.IncomeStatementData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.support.calc.BigDecimals;

@Service
@RequiredArgsConstructor
public class API3MetadataSerialiser {

    public static final String VERSION = "1.2";
    private final Clock clock;

    public MetadataMap serializeToKeriMap(ReportEntity reportEntity) {
        MetadataMap globalMetadataMap = MetadataBuilder.createMap();
        globalMetadataMap.put("org", serialiseOrganisation(reportEntity.getOrganisation()));
        globalMetadataMap.put("subType", reportEntity.getType().name());
        globalMetadataMap.put("interval", reportEntity.getIntervalType().name());
        globalMetadataMap.put("year", reportEntity.getYear().toString());
        globalMetadataMap.put("mode", reportEntity.getMode().name());
        globalMetadataMap.put("ver", BigInteger.valueOf(reportEntity.getVer()));

        MetadataMap dataMap;
        switch (reportEntity.getType()) {
            case BALANCE_SHEET -> dataMap = serialiseBalanceSheetData(
                    reportEntity.getBalanceSheetReportData().orElseThrow());
            case INCOME_STATEMENT -> dataMap = serialiseIncomeStatementData(
                    reportEntity.getIncomeStatementReportData().orElseThrow());
            default -> throw new IllegalArgumentException(
                    "Unsupported report type: %s".formatted(reportEntity.getType()));
        }
        try {
            // Convert the hash to a hex String and put into globalDataMap
            globalMetadataMap.put("dataHash", HexUtil.encodeHexString(Sha256Hash.hash(
                    dataMap.toJson().getBytes())));
        } catch (CborException e) {
            throw new IllegalArgumentException("Failed to calculate data hash", e);
        }
        return globalMetadataMap;
    }

    public MetadataMap serialiseToMetadataMap(ReportEntity reportEntity,
                                              long creationSlot) {
        MetadataMap globalMetadataMap = MetadataBuilder.createMap();

        // Metadata Section
        globalMetadataMap.put("metadata", createMetadataSection(creationSlot));

        // Organisation Section
        Organisation organisation = reportEntity.getOrganisation();
        globalMetadataMap.put("org", serialiseOrganisation(organisation));

        // Report Data Section
        globalMetadataMap.put("type", "REPORT");
        globalMetadataMap.put("subType", reportEntity.getType().name());
        globalMetadataMap.put("interval", reportEntity.getIntervalType().name());
        globalMetadataMap.put("year", reportEntity.getYear().toString());
        globalMetadataMap.put("mode", reportEntity.getMode().name());
        globalMetadataMap.put("ver", BigInteger.valueOf(reportEntity.getVer()));

        reportEntity.getPeriod().ifPresent(period -> globalMetadataMap.put("period", BigInteger.valueOf(period)));

        // Data Section
        switch (reportEntity.getType()) {
            case BALANCE_SHEET -> globalMetadataMap.put("data", serialiseBalanceSheetData(
                    reportEntity.getBalanceSheetReportData().orElseThrow()));
            case INCOME_STATEMENT -> globalMetadataMap.put("data", serialiseIncomeStatementData(
                    reportEntity.getIncomeStatementReportData().orElseThrow()));
            default -> throw new IllegalArgumentException("Unsupported report type: %s".formatted(reportEntity.getType()));
        }

        return globalMetadataMap;
    }

    private MetadataMap createMetadataSection(long creationSlot) {
        MetadataMap metadataMap = MetadataBuilder.createMap();
        Instant now = Instant.now(clock);

        metadataMap.put("creation_slot", BigInteger.valueOf(creationSlot));
        metadataMap.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(now));
        metadataMap.put("version", VERSION);

        return metadataMap;
    }

    private static MetadataMap serialiseOrganisation(org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation organisation) {
        MetadataMap orgMap = MetadataBuilder.createMap();

        orgMap.put("id", organisation.getId());
        orgMap.put("name", organisation.getName());
        orgMap.put("tax_id_number", organisation.getTaxIdNumber());
        orgMap.put("currency_id", organisation.getCurrencyId());
        orgMap.put("country_code", organisation.getCountryCode());

        return orgMap;
    }

    private static MetadataMap serialiseBalanceSheetData(BalanceSheetData balanceSheetData) {
        MetadataMap dataMap = MetadataBuilder.createMap();

        // Assets
        MetadataMap assetsMap = MetadataBuilder.createMap();
        balanceSheetData.getAssets().ifPresent(assets -> {
            assets.getNonCurrentAssets().ifPresent(nca -> {
                MetadataMap nonCurrentAssetsMap = MetadataBuilder.createMap();
                nca.getTangibleAssets().ifPresent(value -> nonCurrentAssetsMap.put("tangible_assets", BigDecimals.normaliseString(value)));
                nca.getIntangibleAssets().ifPresent(value -> nonCurrentAssetsMap.put("intangible_assets", BigDecimals.normaliseString(value)));
                nca.getInvestments().ifPresent(value -> nonCurrentAssetsMap.put("investments", BigDecimals.normaliseString(value)));
                nca.getFinancialAssets().ifPresent(value -> nonCurrentAssetsMap.put("financial_assets", BigDecimals.normaliseString(value)));
                assetsMap.put("non_current_assets", nonCurrentAssetsMap);
            });

            assets.getCurrentAssets().ifPresent(ca -> {
                MetadataMap currentAssetsMap = MetadataBuilder.createMap();
                ca.getPrepaymentsAndOtherShortTermAssets().ifPresent(value -> currentAssetsMap.put("prepayments_and_other_short_term_assets", BigDecimals.normaliseString(value)));
                ca.getOtherReceivables().ifPresent(value -> currentAssetsMap.put("other_receivables", BigDecimals.normaliseString(value)));
                ca.getCryptoAssets().ifPresent(value -> currentAssetsMap.put("crypto_assets", BigDecimals.normaliseString(value)));
                ca.getCashAndCashEquivalents().ifPresent(value -> currentAssetsMap.put("cash_and_cash_equivalents", BigDecimals.normaliseString(value)));
                assetsMap.put("current_assets", currentAssetsMap);
            });

            dataMap.put("assets", assetsMap);
        });

        // Liabilities
        MetadataMap liabilitiesMap = MetadataBuilder.createMap();
        balanceSheetData.getLiabilities().ifPresent(liabilities -> {
            liabilities.getNonCurrentLiabilities().ifPresent(ncl -> {
                MetadataMap nonCurrentLiabilitiesMap = MetadataBuilder.createMap();
                ncl.getProvisions().ifPresent(value -> nonCurrentLiabilitiesMap.put("provisions", BigDecimals.normaliseString(value)));
                liabilitiesMap.put("non_current_liabilities", nonCurrentLiabilitiesMap);
            });

            liabilities.getCurrentLiabilities().ifPresent(cl -> {
                MetadataMap currentLiabilitiesMap = MetadataBuilder.createMap();
                cl.getTradeAccountsPayables().ifPresent(value -> currentLiabilitiesMap.put("trade_accounts_payables", BigDecimals.normaliseString(value)));
                cl.getOtherShortTermLiabilities().ifPresent(value -> currentLiabilitiesMap.put("other_short_term_liabilities", BigDecimals.normaliseString(value)));
                cl.getAccrualsAndShortTermProvisions().ifPresent(value -> currentLiabilitiesMap.put("accruals_and_short_term_provisions", BigDecimals.normaliseString(value)));
                liabilitiesMap.put("current_liabilities", currentLiabilitiesMap);
            });

            dataMap.put("liabilities", liabilitiesMap);
        });

        // Capital
        MetadataMap capitalMap = MetadataBuilder.createMap();
        balanceSheetData.getCapital().ifPresent(capital -> {
            capital.getCapital().ifPresent(value -> capitalMap.put("capital", BigDecimals.normaliseString(value)));
            capital.getResultsCarriedForward().ifPresent(value -> capitalMap.put("results_carried_forward", BigDecimals.normaliseString(value)));
            capital.getProfitForTheYear().ifPresent(value -> capitalMap.put("profit_for_the_year", BigDecimals.normaliseString(value)));
        });
        dataMap.put("capital", capitalMap);

        return dataMap;
    }

    private static MetadataMap serialiseIncomeStatementData(IncomeStatementData incomeStatementData) {
        MetadataMap dataMap = MetadataBuilder.createMap();

        incomeStatementData.getRevenues().ifPresent(revenues -> {
            MetadataMap revenuesMap = MetadataBuilder.createMap();
            revenues.getOtherIncome().ifPresent(value -> revenuesMap.put("other_income", BigDecimals.normaliseString(value)));
            revenues.getBuildOfLongTermProvision().ifPresent(value -> revenuesMap.put("build_of_long_term_provision", BigDecimals.normaliseString(value)));
            dataMap.put("revenues", revenuesMap);
        });

        incomeStatementData.getCostOfGoodsAndServices().ifPresent(cogs -> {
            MetadataMap cogsMap = MetadataBuilder.createMap();
            cogs.getExternalServices().ifPresent(value -> cogsMap.put("external_services", BigDecimals.normaliseString(value)));
            dataMap.put("cost_of_goods_and_services", cogsMap);
        });

        incomeStatementData.getOperatingExpenses().ifPresent(opex -> {
            MetadataMap opexMap = MetadataBuilder.createMap();
            opex.getPersonnelExpenses().ifPresent(value -> opexMap.put("personnel_expenses", BigDecimals.normaliseString(value)));
            opex.getGeneralAndAdministrativeExpenses().ifPresent(value -> opexMap.put("general_and_administrative_expenses", BigDecimals.normaliseString(value)));
            opex.getDepreciationAndImpairmentLossesOnTangibleAssets().ifPresent(value -> opexMap.put("depreciation_and_impairment_losses_on_tangible_assets", BigDecimals.normaliseString(value)));
            opex.getAmortizationOnIntangibleAssets().ifPresent(value -> opexMap.put("amortization_on_intangible_assets", BigDecimals.normaliseString(value)));
            opex.getRentExpenses().ifPresent(value -> opexMap.put("rent_expenses", BigDecimals.normaliseString(value)));
            dataMap.put("operating_expenses", opexMap);
        });

        incomeStatementData.getProfitForTheYear().ifPresent(value -> dataMap.put("profit_for_the_year", BigDecimals.normaliseString(value)));

        incomeStatementData.getExtraordinaryIncome().ifPresent(extraordinaryIncome -> {
            MetadataMap extraordinaryIncomeMap = MetadataBuilder.createMap();
            extraordinaryIncome.getExtraordinaryExpenses().ifPresent(value -> extraordinaryIncomeMap.put("extraordinary_expenses", BigDecimals.normaliseString(value)));
            dataMap.put("extraordinary_income", extraordinaryIncomeMap);
        });

        incomeStatementData.getFinancialIncome().ifPresent(financialIncome -> {
            MetadataMap financialIncomeMap = MetadataBuilder.createMap();
            financialIncome.getFinancialExpenses().ifPresent(value -> financialIncomeMap.put("financial_expenses", BigDecimals.normaliseString(value)));
            financialIncome.getFinancialRevenues().ifPresent(value -> financialIncomeMap.put("financial_revenues", BigDecimals.normaliseString(value)));
            financialIncome.getStakingRewardsIncome().ifPresent(value -> financialIncomeMap.put("staking_rewards_income", BigDecimals.normaliseString(value)));
            financialIncome.getNetIncomeOptionsSale().ifPresent(value -> financialIncomeMap.put("net_income_options_sale", BigDecimals.normaliseString(value)));
            financialIncome.getRealisedGainsOnSaleOfCryptocurrencies().ifPresent(value -> financialIncomeMap.put("realised_gains_on_sale_of_cryptocurrencies", BigDecimals.normaliseString(value)));
            dataMap.put("financial_income", financialIncomeMap);
        });

        incomeStatementData.getTaxExpenses().ifPresent(taxExpenses -> {
            MetadataMap taxExpensesMap = MetadataBuilder.createMap();
            taxExpenses.getDirectTaxes().ifPresent(value -> taxExpensesMap.put("direct_taxes", BigDecimals.normaliseString(value)));
            dataMap.put("tax_expenses", taxExpensesMap);
        });

        return dataMap;
    }

}
