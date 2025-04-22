package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports;

import static jakarta.persistence.EnumType.STRING;

import java.time.LocalDate;
import java.util.Optional;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import io.vavr.control.Either;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportMode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.support.spring_audit.CommonDateOnlyEntity;

@Entity(name = "blockchain_publisher.report.ReportEntity")
@Table(name = "blockchain_publisher_report")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners({ AuditingEntityListener.class })
public class ReportEntity extends CommonDateOnlyEntity implements Persistable<String> {

    @Id
    @Column(name = "report_id", nullable = false, length = 64)
    @NotBlank
    @Getter
    @Setter
    private String reportId;

    @Override
    public String getId() {
        return reportId;
    }

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "organisation_id")),
            @AttributeOverride(name = "name", column = @Column(name = "organisation_name")),
            @AttributeOverride(name = "countryCode", column = @Column(name = "organisation_country_code")),
            @AttributeOverride(name = "taxIdNumber", column = @Column(name = "organisation_tax_id_number")),
            @AttributeOverride(name = "currencyId", column = @Column(name = "organisation_currency_id"))
    })
    @Getter
    @Setter
    private Organisation organisation;

    @Enumerated(STRING)
    @Column(name = "type", nullable = false)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @NotNull
    @Getter
    @Setter
    private ReportType type;

    @Enumerated(STRING)
    @Column(name = "interval_type", nullable = false)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @NotNull
    @Getter
    @Setter
    private IntervalType intervalType;

    @Column(name = "year", nullable = false)
    @Min(1900)
    @Max(4000)
    @Getter
    @Setter
    private Short year;

    @Column(name = "period")
    @Min(1)
    @Max(12)
    @Nullable
    private Short period;

    @Enumerated(STRING)
    @Column(name = "mode", nullable = false)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Getter
    @Setter
    private ReportMode mode;

    @Column(name = "date", nullable = false)
    @NotNull
    @Getter
    @Setter
    private LocalDate date;

    @Column(name = "ver", nullable = false)
    @NotNull
    @Getter
    @Setter
    private long ver;

    @Nullable
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "transactionHash", column = @Column(name = "l1_transaction_hash", length = 64)),
            @AttributeOverride(name = "absoluteSlot", column = @Column(name = "l1_absolute_slot")),
            @AttributeOverride(name = "creationSlot", column = @Column(name = "l1_creation_slot")),
            @AttributeOverride(name = "finalityScore", column = @Column(name = "l1_finality_score", columnDefinition = "blockchain_publisher_finality_score_type")),
            @AttributeOverride(name = "publishStatus", column = @Column(name = "l1_publish_status", columnDefinition = "blockchain_publisher_blockchain_publish_status_type"))
    })
    private L1SubmissionData l1SubmissionData;

    public Optional<L1SubmissionData> getL1SubmissionData() {
        return Optional.ofNullable(l1SubmissionData);
    }

    public void setL1SubmissionData(Optional<L1SubmissionData> l1SubmissionData) {
        this.l1SubmissionData = l1SubmissionData.orElse(null);
    }

    @Embedded
    @AttributeOverrides({
            // Balance Sheet::Assets - Non-Current Assets
            @AttributeOverride(name = "assets.nonCurrentAssets.tangibleAssets", column = @Column(name = "data_balance_sheet__assets_non_current_tangible_assets")),
            @AttributeOverride(name = "assets.nonCurrentAssets.intangibleAssets", column = @Column(name = "data_balance_sheet__assets_non_current_intangible_assets")),
            @AttributeOverride(name = "assets.nonCurrentAssets.investments", column = @Column(name = "data_balance_sheet__assets_non_current_investments")),
            @AttributeOverride(name = "assets.nonCurrentAssets.financialAssets", column = @Column(name = "data_balance_sheet__assets_non_current_financial_assets")),

            // Balance Sheet::Assets - Current Assets
            @AttributeOverride(name = "assets.currentAssets.prepaymentsAndOtherShortTermAssets", column = @Column(name = "data_balance_sheet__assets_current_prepayments_short_assets")),
            @AttributeOverride(name = "assets.currentAssets.otherReceivables", column = @Column(name = "data_balance_sheet__assets_current_other_receivables")),
            @AttributeOverride(name = "assets.currentAssets.cryptoAssets", column = @Column(name = "data_balance_sheet__assets_current_crypto_assets")),
            @AttributeOverride(name = "assets.currentAssets.cashAndCashEquivalents", column = @Column(name = "data_balance_sheet__assets_current_cash_and_equivalen")),

            // Balance Sheet::Liabilities - Non-Current Liabilities
            @AttributeOverride(name = "liabilities.nonCurrentLiabilities.provisions", column = @Column(name = "data_balance_sheet__liabilities_non_current_provisions")),

            // Balance Sheet::Liabilities - Current Liabilities
            @AttributeOverride(name = "liabilities.currentLiabilities.tradeAccountsPayables", column = @Column(name = "data_balance_sheet__liabilities_current_trade_accounts")),
            @AttributeOverride(name = "liabilities.currentLiabilities.otherShortTermLiabilities", column = @Column(name = "data_balance_sheet__liabilities_current_other_liabilities")),
            @AttributeOverride(name = "liabilities.currentLiabilities.accrualsAndShortTermProvisions", column = @Column(name = "data_balance_sheet__liabilities_current_accruals_and_short")),

            // Balance Sheet::Capital
            @AttributeOverride(name = "capital.capital", column = @Column(name = "data_balance_sheet__capital_capital")),
            @AttributeOverride(name = "capital.profitForTheYear", column = @Column(name = "data_balance_sheet__capital_profit_for_the_year")),
            @AttributeOverride(name = "capital.resultsCarriedForward", column = @Column(name = "data_balance_sheet__capital_results_carried_forward"))
    })
    @Nullable
    private BalanceSheetData balanceSheetReportData;

    @Embedded
    @AttributeOverrides({
            // Revenues
            @AttributeOverride(name = "revenues.otherIncome", column = @Column(name = "data_income_statement__revenues_other_income")),
            @AttributeOverride(name = "revenues.buildOfLongTermProvision", column = @Column(name = "data_income_statement__revenues_build_long_term_provision")),

            @AttributeOverride(name = "costOfGoodsAndServices.externalServices", column = @Column(name = "data_income_statement__cost_goods_and_services_external_serv")), // was too long

            // Operating Expenses
            @AttributeOverride(name = "operatingExpenses.personnelExpenses", column = @Column(name = "data_income_statement__operating_expenses_personnel_expenses")),
            @AttributeOverride(name = "operatingExpenses.generalAndAdministrativeExpenses", column = @Column(name = "data_income_statement__operating_expenses_general_admin_ex")),
            @AttributeOverride(name = "operatingExpenses.depreciationAndImpairmentLossesOnTangibleAssets", column = @Column(name = "data_income_statement__operating_expenses_depreciation_tang")),
            @AttributeOverride(name = "operatingExpenses.amortizationOnIntangibleAssets", column = @Column(name = "data_income_statement__operating_expenses_amortization_int")),
            @AttributeOverride(name = "operatingExpenses.rentExpenses", column = @Column(name = "data_income_statement__operating_expenses_rent_expenses")),

            // Financial Income
            @AttributeOverride(name = "financialIncome.financialRevenues", column = @Column(name = "data_income_statement__financial_income_financial_revenues")),
            @AttributeOverride(name = "financialIncome.financialExpenses", column = @Column(name = "data_income_statement__financial_income_financial_expenses")),
            @AttributeOverride(name = "financialIncome.realisedGainsOnSaleOfCryptocurrencies", column = @Column(name = "data_income_statement__financial_income_realised_gains")),
            @AttributeOverride(name = "financialIncome.stakingRewardsIncome", column = @Column(name = "data_income_statement__financial_income_staking_rewards")),
            @AttributeOverride(name = "financialIncome.netIncomeOptionsSale", column = @Column(name = "data_income_statement__financial_income_net_income_opt")),

            // Extraordinary Income
            @AttributeOverride(name = "extraordinaryIncome.extraordinaryExpenses", column = @Column(name = "data_income_statement__operating_expenses_extraordin_exp")),

            // Tax Expenses
            @AttributeOverride(name = "taxExpenses.directTaxes", column = @Column(name = "data_income_statement__tax_expenses_direct_taxes")),

            @AttributeOverride(name = "extraordinaryIncome.extraordinaryExpenses", column = @Column(name = "data_income_statement__operating_expenses_extraordin_exp")),
            @AttributeOverride(name = "profitForTheYear", column = @Column(name = "data_income_statement__profit_for_the_year"))
    })
    @Nullable
    private IncomeStatementData incomeStatementReportData;

    public void setData(Either<BalanceSheetData, IncomeStatementData> dataE) {
        if (dataE.isLeft()) {
            setBalanceSheetReportData(Optional.of(dataE.getLeft()));
            return;
        }
        if (dataE.isRight()) {
            setIncomeStatementReportData(Optional.of(dataE.get()));
            return;
        }
    }

    public void setBalanceSheetReportData(Optional<BalanceSheetData> balanceSheetReportData) {
        if (type != ReportType.BALANCE_SHEET) {
            throw new IllegalStateException("Report type is not BALANCE_SHEET!");
        }

        this.balanceSheetReportData = balanceSheetReportData.orElse(null);
    }

    public void setIncomeStatementReportData(Optional<IncomeStatementData> incomeStatementReportData) {
        if (type != ReportType.INCOME_STATEMENT) {
            throw new IllegalStateException("Report type is not INCOME_STATEMENT!");
        }

        this.incomeStatementReportData = incomeStatementReportData.orElse(null);
    }

    public Optional<BalanceSheetData> getBalanceSheetReportData() {
        return Optional.ofNullable(balanceSheetReportData);
    }

    public Optional<IncomeStatementData> getIncomeStatementReportData() {
        return Optional.ofNullable(incomeStatementReportData);
    }

    public Optional<@Min(1) @Max(12) Short> getPeriod() {
        return Optional.ofNullable(period);
    }

    public void setPeriod(Optional<@Min(1) @Max(12) Short> period) {
        this.period = period.orElse(null);
    }

}
