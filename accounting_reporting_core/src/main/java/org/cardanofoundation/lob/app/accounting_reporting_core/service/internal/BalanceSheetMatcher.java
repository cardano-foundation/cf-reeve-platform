package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.lang.Nullable;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData;

public class BalanceSheetMatcher {

    private BalanceSheetMatcher() {
        // Prevent instantiation
    }

    public static boolean matches(Optional<BalanceSheetData> templateO, Optional<BalanceSheetData> actualO) {
        if (templateO.isEmpty()) return true;
        if (actualO.isEmpty()) return false;

        BalanceSheetData template = templateO.get();
        BalanceSheetData actual = actualO.get();

        return matches(template.getAssets().orElse(null), actual.getAssets().orElse(null)) &&
                matches(template.getLiabilities().orElse(null), actual.getLiabilities().orElse(null)) &&
                matches(template.getCapital().orElse(null), actual.getCapital().orElse(null));
    }

    private static boolean matches(@Nullable BalanceSheetData.Assets template, @Nullable BalanceSheetData.Assets actual) {
        if (template == null) return true;
        if (actual == null) return false;

        return matches(template.getNonCurrentAssets().orElse(null), actual.getNonCurrentAssets().orElse(null)) &&
                matches(template.getCurrentAssets().orElse(null), actual.getCurrentAssets().orElse(null));
    }

    private static boolean matches(@Nullable BalanceSheetData.Assets.NonCurrentAssets template, @Nullable BalanceSheetData.Assets.NonCurrentAssets actual) {
        if (template == null) return true;
        if (actual == null) return false;

        return matchesValue(template.getTangibleAssets(), actual.getTangibleAssets()) &&
                matchesValue(template.getIntangibleAssets(), actual.getIntangibleAssets()) &&
                matchesValue(template.getInvestments(), actual.getInvestments()) &&
                matchesValue(template.getFinancialAssets(), actual.getFinancialAssets());
    }

    private static boolean matches(@Nullable BalanceSheetData.Assets.CurrentAssets template, @Nullable BalanceSheetData.Assets.CurrentAssets actual) {
        if (template == null) return true;
        if (actual == null) return false;

        return matchesValue(template.getPrepaymentsAndOtherShortTermAssets(), actual.getPrepaymentsAndOtherShortTermAssets()) &&
                matchesValue(template.getOtherReceivables(), actual.getOtherReceivables()) &&
                matchesValue(template.getCryptoAssets(), actual.getCryptoAssets()) &&
                matchesValue(template.getCashAndCashEquivalents(), actual.getCashAndCashEquivalents());
    }

    private static boolean matches(@Nullable BalanceSheetData.Liabilities template, @Nullable BalanceSheetData.Liabilities actual) {
        if (template == null) return true;
        if (actual == null) return false;

        return matches(template.getNonCurrentLiabilities().orElse(null), actual.getNonCurrentLiabilities().orElse(null)) &&
                matches(template.getCurrentLiabilities().orElse(null), actual.getCurrentLiabilities().orElse(null));
    }

    private static boolean matches(@Nullable BalanceSheetData.Liabilities.NonCurrentLiabilities template, @Nullable BalanceSheetData.Liabilities.NonCurrentLiabilities actual) {
        if (template == null) return true;
        if (actual == null) return false;

        return matchesValue(template.getProvisions(), actual.getProvisions());
    }

    private static boolean matches(@Nullable BalanceSheetData.Liabilities.CurrentLiabilities template, @Nullable BalanceSheetData.Liabilities.CurrentLiabilities actual) {
        if (template == null) return true;
        if (actual == null) return false;

        return matchesValue(template.getTradeAccountsPayables(), actual.getTradeAccountsPayables()) &&
                matchesValue(template.getOtherShortTermLiabilities(), actual.getOtherShortTermLiabilities()) &&
                matchesValue(template.getAccrualsAndShortTermProvisions(), actual.getAccrualsAndShortTermProvisions());
    }

    private static boolean matches(@Nullable BalanceSheetData.Capital template, @Nullable BalanceSheetData.Capital actual) {
        if (template == null) return true;
        if (actual == null) return false;

        return matchesValue(template.getCapital(), actual.getCapital()) &&
                matchesValue(template.getProfitForTheYear(), actual.getProfitForTheYear()) &&
                matchesValue(template.getResultsCarriedForward(), actual.getResultsCarriedForward());
    }

    private static boolean matchesValue(Optional<BigDecimal> expected, Optional<BigDecimal> actual) {
        return expected.map(value -> actual.map(value::compareTo).orElse(-1) == 0).orElse(true);
    }

}
