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
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.PublishError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;

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
    private PublishError canPublishError;

    private String documentCurrencyCustomerCode;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime publishDate;

    private String publishedBy;

    private String createdBy;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")

    private LocalDateTime createdAt;

    private String updatedBy;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")

    private LocalDateTime updatedAt;

    @Nullable
    private String blockChainHash;

    @Nullable
    private String ledgerDispatchStatusErrorReason;

    private Optional<Problem> error;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate date;

    @Schema(example = "265306.12")
    private String tangibleAssets;

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
    private String otherShortTermLiabilities;

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
    private String externalServices;

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
    private String directTaxes;

    public static ReportView fromEntity(ReportEntity reportEntity) {
        ReportView reportResponseView = new ReportView();
        reportResponseView.setReportId(reportEntity.getReportId());
        reportResponseView.setDocumentCurrencyCustomerCode(reportEntity.getOrganisation().getCurrencyId());
        reportResponseView.setOrganisationId(reportEntity.getOrganisation().getId());
        reportResponseView.setType(reportEntity.getType());
        reportResponseView.setPublishDate(reportEntity.getLedgerDispatchDate());
        reportResponseView.setPublishedBy(reportEntity.getPublishedBy());
        reportResponseView.setCreatedBy(reportEntity.getCreatedBy());
        reportResponseView.setCreatedAt(reportEntity.getCreatedAt());
        reportResponseView.setUpdatedBy(reportEntity.getUpdatedBy());
        reportResponseView.setUpdatedAt(reportEntity.getUpdatedAt());
        reportResponseView.setIntervalType(reportEntity.getIntervalType());
        reportResponseView.setYear(reportEntity.getYear());
        reportResponseView.setPeriod(reportEntity.getPeriod());
        reportResponseView.setDate(reportEntity.getDate());
        reportResponseView.setPublish(reportEntity.getLedgerDispatchApproved());

        if (reportEntity.getLedgerDispatchReceipt().isPresent()) {
            reportResponseView.setBlockChainHash(reportEntity.getLedgerDispatchReceipt().get().getPrimaryBlockchainHash());
        }

        reportResponseView.setLedgerDispatchStatusErrorReason(reportEntity.getLedgerDispatchStatusErrorReason());
        reportResponseView.setCanBePublish(reportEntity.getIsReadyToPublish());
        reportResponseView.setCanPublishError(reportEntity.getPublishError());
        reportResponseView.setVer(reportEntity.getVer());
        //BalanceSheet
        reportEntity.getBalanceSheetReportData().flatMap(balanceSheetData -> balanceSheetData.getAssets().flatMap(assets -> assets.getNonCurrentAssets().flatMap(nonCurrentAssets -> nonCurrentAssets.getTangibleAssets()))).ifPresent(bigDecimal -> reportResponseView.setTangibleAssets(bigDecimal.toString()));
        reportEntity.getBalanceSheetReportData().flatMap(balanceSheetData -> balanceSheetData.getAssets().flatMap(assets -> assets.getNonCurrentAssets().flatMap(nonCurrentAssets -> nonCurrentAssets.getIntangibleAssets()))).ifPresent(bigDecimal -> reportResponseView.setIntangibleAssets(bigDecimal.toString()));
        reportEntity.getBalanceSheetReportData().flatMap(balanceSheetData -> balanceSheetData.getAssets().flatMap(assets -> assets.getNonCurrentAssets().flatMap(nonCurrentAssets -> nonCurrentAssets.getInvestments()))).ifPresent(bigDecimal -> reportResponseView.setInvestments(bigDecimal.toString()));
        reportEntity.getBalanceSheetReportData().flatMap(balanceSheetData -> balanceSheetData.getAssets().flatMap(assets -> assets.getNonCurrentAssets().flatMap(nonCurrentAssets -> nonCurrentAssets.getFinancialAssets()))).ifPresent(bigDecimal -> reportResponseView.setFinancialAssets(bigDecimal.toString()));

        reportEntity.getBalanceSheetReportData().flatMap(balanceSheetData -> balanceSheetData.getAssets().flatMap(assets -> assets.getCurrentAssets().flatMap(currentAssets -> currentAssets.getPrepaymentsAndOtherShortTermAssets()))).ifPresent(bigDecimal -> reportResponseView.setPrepaymentsAndOtherShortTermAssets(bigDecimal.toString()));
        reportEntity.getBalanceSheetReportData().flatMap(balanceSheetData -> balanceSheetData.getAssets().flatMap(assets -> assets.getCurrentAssets().flatMap(currentAssets -> currentAssets.getOtherReceivables()))).ifPresent(bigDecimal -> reportResponseView.setOtherReceivables(bigDecimal.toString()));
        reportEntity.getBalanceSheetReportData().flatMap(balanceSheetData -> balanceSheetData.getAssets().flatMap(assets -> assets.getCurrentAssets().flatMap(currentAssets -> currentAssets.getCryptoAssets()))).ifPresent(bigDecimal -> reportResponseView.setCryptoAssets(bigDecimal.toString()));
        reportEntity.getBalanceSheetReportData().flatMap(balanceSheetData -> balanceSheetData.getAssets().flatMap(assets -> assets.getCurrentAssets().flatMap(currentAssets -> currentAssets.getCashAndCashEquivalents()))).ifPresent(bigDecimal -> reportResponseView.setCashAndCashEquivalents(bigDecimal.toString()));

        reportEntity.getBalanceSheetReportData().flatMap(balanceSheetData -> balanceSheetData.getLiabilities().flatMap(liabilities -> liabilities.getNonCurrentLiabilities().flatMap(nonCurrentLiabilities -> nonCurrentLiabilities.getProvisions()))).ifPresent(bigDecimal -> reportResponseView.setProvisions(bigDecimal.toString()));

        reportEntity.getBalanceSheetReportData().flatMap(balanceSheetData -> balanceSheetData.getLiabilities().flatMap(liabilities -> liabilities.getCurrentLiabilities().flatMap(currentLiabilities -> currentLiabilities.getTradeAccountsPayables()))).ifPresent(bigDecimal -> reportResponseView.setTradeAccountsPayables(bigDecimal.toString()));
        reportEntity.getBalanceSheetReportData().flatMap(balanceSheetData -> balanceSheetData.getLiabilities().flatMap(liabilities -> liabilities.getCurrentLiabilities().flatMap(currentLiabilities -> currentLiabilities.getOtherShortTermLiabilities()))).ifPresent(bigDecimal -> reportResponseView.setOtherShortTermLiabilities(bigDecimal.toString()));
        reportEntity.getBalanceSheetReportData().flatMap(balanceSheetData -> balanceSheetData.getLiabilities().flatMap(liabilities -> liabilities.getCurrentLiabilities().flatMap(currentLiabilities -> currentLiabilities.getAccrualsAndShortTermProvisions()))).ifPresent(bigDecimal -> reportResponseView.setAccrualsAndShortTermProvisions(bigDecimal.toString()));

        reportEntity.getBalanceSheetReportData().flatMap(balanceSheetData -> balanceSheetData.getCapital().flatMap(capital -> capital.getCapital())).ifPresent(bigDecimal -> reportResponseView.setCapital(bigDecimal.toString()));
        reportEntity.getBalanceSheetReportData().flatMap(balanceSheetData -> balanceSheetData.getCapital().flatMap(capital -> capital.getProfitForTheYear())).ifPresent(bigDecimal -> reportResponseView.setProfitForTheYear(bigDecimal.toString()));
        reportEntity.getBalanceSheetReportData().flatMap(balanceSheetData -> balanceSheetData.getCapital().flatMap(capital -> capital.getResultsCarriedForward())).ifPresent(bigDecimal -> reportResponseView.setResultsCarriedForward(bigDecimal.toString()));

        //IncomeStatement
        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getRevenues().flatMap(revenues -> revenues.getOtherIncome())).ifPresent(bigDecimal -> reportResponseView.setOtherIncome(bigDecimal.toString()));
        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getRevenues().flatMap(revenues -> revenues.getBuildOfLongTermProvision())).ifPresent(bigDecimal -> reportResponseView.setBuildOfLongTermProvision(bigDecimal.toString()));

        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getCostOfGoodsAndServices().flatMap(costOfGoodsAndServices -> costOfGoodsAndServices.getExternalServices())).ifPresent(bigDecimal -> reportResponseView.setExternalServices(bigDecimal.toString()));

        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getOperatingExpenses().flatMap(operatingExpenses -> operatingExpenses.getPersonnelExpenses())).ifPresent(bigDecimal -> reportResponseView.setPersonnelExpenses(bigDecimal.toString()));
        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getOperatingExpenses().flatMap(operatingExpenses -> operatingExpenses.getGeneralAndAdministrativeExpenses())).ifPresent(bigDecimal -> reportResponseView.setGeneralAndAdministrativeExpenses(bigDecimal.toString()));
        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getOperatingExpenses().flatMap(operatingExpenses -> operatingExpenses.getDepreciationAndImpairmentLossesOnTangibleAssets())).ifPresent(bigDecimal -> reportResponseView.setDepreciationAndImpairmentLossesOnTangibleAssets(bigDecimal.toString()));
        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getOperatingExpenses().flatMap(operatingExpenses -> operatingExpenses.getAmortizationOnIntangibleAssets())).ifPresent(bigDecimal -> reportResponseView.setAmortizationOnIntangibleAssets(bigDecimal.toString()));
        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getOperatingExpenses().flatMap(operatingExpenses -> operatingExpenses.getRentExpenses())).ifPresent(bigDecimal -> reportResponseView.setRentExpenses(bigDecimal.toString()));

        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getFinancialIncome().flatMap(financialIncome -> financialIncome.getFinancialRevenues())).ifPresent(bigDecimal -> reportResponseView.setFinancialRevenues(bigDecimal.toString()));
        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getFinancialIncome().flatMap(financialIncome -> financialIncome.getFinancialExpenses())).ifPresent(bigDecimal -> reportResponseView.setFinancialExpenses(bigDecimal.toString()));
        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getFinancialIncome().flatMap(financialIncome -> financialIncome.getRealisedGainsOnSaleOfCryptocurrencies())).ifPresent(bigDecimal -> reportResponseView.setRealisedGainsOnSaleOfCryptocurrencies(bigDecimal.toString()));
        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getFinancialIncome().flatMap(financialIncome -> financialIncome.getStakingRewardsIncome())).ifPresent(bigDecimal -> reportResponseView.setStakingRewardsIncome(bigDecimal.toString()));
        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getFinancialIncome().flatMap(financialIncome -> financialIncome.getNetIncomeOptionsSale())).ifPresent(bigDecimal -> reportResponseView.setNetIncomeOptionsSale(bigDecimal.toString()));

        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getExtraordinaryIncome().flatMap(extraordinaryIncome -> extraordinaryIncome.getExtraordinaryExpenses())).ifPresent(bigDecimal -> reportResponseView.setExtraordinaryExpenses(bigDecimal.toString()));

        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getTaxExpenses().flatMap(taxExpenses -> taxExpenses.getDirectTaxes())).ifPresent(bigDecimal -> reportResponseView.setDirectTaxes(bigDecimal.toString()));
        reportEntity.getIncomeStatementReportData().flatMap(incomeStatementData -> incomeStatementData.getProfitForTheYear()).ifPresent(bigDecimal -> reportResponseView.setProfitForTheYear(bigDecimal.toString()));

        return reportResponseView;
    }

}
