package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import java.util.Optional;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.*;

@Service("accounting_reporting_core.BalanceSheetConverter")
@RequiredArgsConstructor
public class BalanceSheetConverter {

    public Optional<BalanceSheetData> convertBalanceSheet(Optional<org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData> balanceSheetData) {
        return balanceSheetData.map(bs -> BalanceSheetData.builder()
                .assets(convertAssets(bs.getAssets()).orElse(null))
                .liabilities(convertLiabilities(bs.getLiabilities()).orElse(null))
                .capital(convertCapital(bs.getCapital()).orElse(null))
                .build());
    }

    private Optional<Assets> convertAssets(Optional<org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData.Assets> assets) {
        return assets.map(a -> Assets.builder()
                .nonCurrentAssets(convertNonCurrentAssets(a.getNonCurrentAssets()).orElse(null))
                .currentAssets(convertCurrentAssets(a.getCurrentAssets()).orElse(null))
                .build());
    }

    private Optional<NonCurrentAssets> convertNonCurrentAssets(Optional<org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData.Assets.NonCurrentAssets> nonCurrentAssets) {
        return nonCurrentAssets.map(nc -> NonCurrentAssets.builder()
                .propertyPlantEquipment(nc.getTangibleAssets().orElse(null))
                .intangibleAssets(nc.getIntangibleAssets().orElse(null))
                .investments(nc.getInvestments().orElse(null))
                .financialAssets(nc.getFinancialAssets().orElse(null))
                .build());
    }

    private Optional<CurrentAssets> convertCurrentAssets(Optional<org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData.Assets.CurrentAssets> currentAssets) {
        return currentAssets.map(ca -> CurrentAssets.builder()
                .prepaymentsAndOtherShortTermAssets(ca.getPrepaymentsAndOtherShortTermAssets().orElse(null))
                .otherReceivables(ca.getOtherReceivables().orElse(null))
                .cryptoAssets(ca.getCryptoAssets().orElse(null))
                .cashAndCashEquivalents(ca.getCashAndCashEquivalents().orElse(null))
                .build());
    }

    private Optional<Liabilities> convertLiabilities(Optional<org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData.Liabilities> liabilities) {
        return liabilities.map(l -> Liabilities.builder()
                .nonCurrentLiabilities(convertNonCurrentLiabilities(l.getNonCurrentLiabilities()).orElse(null))
                .currentLiabilities(convertCurrentLiabilities(l.getCurrentLiabilities()).orElse(null))
                .build());
    }

    private Optional<NonCurrentLiabilities> convertNonCurrentLiabilities(Optional<org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData.Liabilities.NonCurrentLiabilities> nonCurrentLiabilities) {
        return nonCurrentLiabilities.map(nc -> NonCurrentLiabilities.builder()
                .provisions(nc.getProvisions().orElse(null))
                .build());
    }

    private Optional<CurrentLiabilities> convertCurrentLiabilities(Optional<org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData.Liabilities.CurrentLiabilities> currentLiabilities) {
        return currentLiabilities.map(cl -> CurrentLiabilities.builder()
                .tradeAccountsPayables(cl.getTradeAccountsPayables().orElse(null))
                .otherCurrentLiabilities(cl.getOtherShortTermLiabilities().orElse(null))
                .accrualsAndShortTermProvisions(cl.getAccrualsAndShortTermProvisions().orElse(null))
                .build());
    }

    private Optional<Capital> convertCapital(Optional<org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData.Capital> capital) {
        return capital.map(c -> Capital.builder()
                .capital(c.getCapital().orElse(null))
                .profitForTheYear(c.getProfitForTheYear().orElse(null))
                .resultsCarriedForward(c.getResultsCarriedForward().orElse(null))
                .build());
    }

}
