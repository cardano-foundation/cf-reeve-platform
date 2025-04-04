package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import javax.annotation.Nullable;

import lombok.Getter;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;

@Getter
@Setter
public class ReportView {

    private String organisationId;

    private String reportId;

    private ReportType type;

    private IntervalType intervalType;

    private Short year;

    private Optional<Short> period;

    private Long ver;

    private Boolean publish;

    private Boolean canBePublish;

    private String documentCurrencyCustomerCode;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime publishDate;

    @Nullable
    private String blockChainHash;

    private Optional<Problem> error;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate date;

    @Schema(example = "265306.12")
    private String propertyPlantEquipment;

    @Schema(example = "63673.47")
    private String intangibleAssets;

    @Schema(example = "106122.45")
    private String investments;

    @Schema(example = "79591.84")
    private String financialAssets;

    @Schema(example = "15918.37")
    private String prepaymentsAndOtherShortTermAssets;

    @Schema(example = "26530.61")
    private String otherReceivables;

    @Schema(example = "53061.22")
    private String cryptoAssets;

    @Schema(example = "39795.92")
    private String cashAndCashEquivalents;

    @Schema(example = "20000.00")
    private String provisions;

    @Schema(example = "15000.00")
    private String tradeAccountsPayables;

    @Schema(example = "10000.00")
    private String otherCurrentLiabilities;

    @Schema(example = "5000.00")
    private String accrualsAndShortTermProvisions;

    @Schema(example = "300000.00")
    private String capital;

    @Schema(example = "100000.00")
    private String profitForTheYear;

    @Schema(example = "200000.00")
    private String resultsCarriedForward;

    @Schema(example = "10000.90")
    private String otherIncome;

    @Schema(example = "1000000.10")
    private String buildOfLongTermProvision;

    @Schema(example = "500000.15")
    private String costOfProvidingServices;

    @Schema(example = "500000.15")
    private String personnelExpenses;

    @Schema(example = "200000.53")
    private String generalAndAdministrativeExpenses;

    @Schema(example = "200000.53")
    private String depreciationAndImpairmentLossesOnTangibleAssets;

    @Schema(example = "200000.53")
    private String amortizationOnIntangibleAssets;

    @Schema(example = "200000.53")
    private String rentExpenses;

    @Schema(example = "200000.53")
    private String financialRevenues;

    @Schema(example = "20000.10")
    private String financialExpenses;

    @Schema(example = "50000.15")
    private String realisedGainsOnSaleOfCryptocurrencies;

    @Schema(example = "10000.53")
    private String stakingRewardsIncome;

    @Schema(example = "100000.10")
    private String netIncomeOptionsSale;

    @Schema(example = "10000.10")
    private String extraordinaryExpenses;

    @Schema(example = "1000.51")
    private String incomeTaxExpense;


}
