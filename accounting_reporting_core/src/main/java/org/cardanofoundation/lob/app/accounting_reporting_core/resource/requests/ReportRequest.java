package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Slf4j
public class ReportRequest extends BaseRequest {

    @Schema(example = "INCOME_STATEMENT")
    private ReportType reportType;

    private IntervalType intervalType;

    @Schema(example = "2023")
    private short year;

    @Schema(example = "3")
    @Nullable
    private short period;

    /**
     * INCOME_STATEMENT
     */

    @Nullable
    @Schema(example = "0")
    private String otherIncome;

    @Nullable
    @Schema(example = "14410204.33")
    private String buildOfLongTermProvision;

    @Nullable
    @Schema(example = "-5819594.52")
    private String costOfProvidingServices;

    @Nullable
    @Schema(example = "947865.18")
    private String financialRevenues;

    @Nullable
    @Schema(example = "103225.52")
    private String netIncomeOptionsSale;

    @Nullable
    @Schema(example = "4550874.99")
    private String realisedGainsOnSaleOfCryptocurrencies;

    @Nullable
    @Schema(example = "6500117.15")
    private String stakingRewardsIncome;

    @Nullable
    @Schema(example = "-4089224.54")
    private String financialExpenses;

    @Nullable
    @Schema(example = "0")
    private String extraordinaryExpenses;

    @Nullable
    @Schema(example = "-102451.91")
    private String incomeTaxExpense;

    @Nullable
    @Schema(example = "-13364269.18")
    private String personnelExpenses;

    @Nullable
    @Schema(example = "-1765633.98")
    private String generalAndAdministrativeExpenses;

    @Nullable
    @Schema(example = "-38316.88")
    private String depreciationAndImpairmentLossesOnTangibleAssets;

    @Nullable
    @Schema(example = "-2320.00")
    private String amortizationOnIntangibleAssets;

    @Nullable
    @Schema(example = "-216536.85")
    private String rentExpenses;

    /**
     * BALANCE_SHEET
     */

    @Nullable
    @Schema(example = "56493.71")
    private String propertyPlantEquipment;

    @Nullable
    @Schema(example = "3480.00")
    private String intangibleAssets;

    @Nullable
    @Schema(example = "24466.99")
    private String investments;

    @Nullable
    @Schema(example = "20394894.94")
    private String financialAssets;

    @Nullable
    @Schema(example = "644311.18")
    private String prepaymentsAndOtherShortTermAssets;

    @Nullable
    @Schema(example = "503067.18")
    private String otherReceivables;

    @Nullable
    @Schema(example = "58499305.14")
    private String cryptoAssets;

    @Nullable
    @Schema(example = "9628010.23")
    private String cashAndCashEquivalents;

    @Nullable
    @Schema(example = "82085632.53")
    private String provisions;

    @Nullable
    @Schema(example = "4812.50")
    private String tradeAccountsPayables;

    @Nullable
    @Schema(example = "617835.67")
    private String otherCurrentLiabilities;

    @Nullable
    @Schema(example = "3523134.10")
    private String accrualsAndShortTermProvisions;

    @Nullable
    @Schema(example = "50000.00")
    private String capital;

    @Nullable
    @Schema(example = "1113939.31")
    private String profitForTheYear;

    @Nullable
    @Schema(example = "2358675.26")
    private String resultsCarriedForward;

}
