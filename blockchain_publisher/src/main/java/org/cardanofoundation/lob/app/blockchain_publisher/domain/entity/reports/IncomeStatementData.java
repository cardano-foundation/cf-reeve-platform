package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports;

import java.math.BigDecimal;
import java.util.Optional;

import jakarta.persistence.Embeddable;

import lombok.*;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Validable;
import org.cardanofoundation.lob.app.support.calc.BigDecimals;
import org.cardanofoundation.lob.app.support.calc.Summable;

@AllArgsConstructor
@Builder(toBuilder = true)
@EqualsAndHashCode
@ToString
@NoArgsConstructor
@Embeddable
public class IncomeStatementData implements Validable, Summable {

    private Revenues revenues;
    private CostOfGoodsAndServices costOfGoodsAndServices;
    private OperatingExpenses operatingExpenses;
    private FinancialIncome financialIncome;
    private ExtraordinaryIncome extraordinaryIncome;
    private TaxExpenses taxExpenses;
    private BigDecimal profitForTheYear;

    @Override
    public boolean isValid() {
        return true;
    }

    public Optional<Revenues> getRevenues() {
        return Optional.ofNullable(revenues);
    }

    public Optional<CostOfGoodsAndServices> getCostOfGoodsAndServices() {
        return Optional.ofNullable(costOfGoodsAndServices);
    }

    public Optional<OperatingExpenses> getOperatingExpenses() {
        return Optional.ofNullable(operatingExpenses);
    }

    public Optional<FinancialIncome> getFinancialIncome() {
        return Optional.ofNullable(financialIncome);
    }

    public Optional<TaxExpenses> getTaxExpenses() {
        return Optional.ofNullable(taxExpenses);
    }

    public Optional<ExtraordinaryIncome> getExtraordinaryIncome() {
        return Optional.ofNullable(extraordinaryIncome);
    }

    public Optional<BigDecimal> getProfitForTheYear() {
        return Optional.of(sumOf());
    }

    @Override
    public BigDecimal sumOf() {
        return BigDecimals.sum(
                revenues,
                costOfGoodsAndServices,
                operatingExpenses,
                financialIncome,
                extraordinaryIncome,
                taxExpenses
        );
    }

    @AllArgsConstructor
    @Builder(toBuilder = true)
    @EqualsAndHashCode
    @ToString
    @NoArgsConstructor
    @Embeddable
    public static class Revenues implements Summable {

        private BigDecimal otherIncome;
        private BigDecimal buildOfLongTermProvision;

        public Optional<BigDecimal> getOtherIncome() {
            return Optional.ofNullable(otherIncome);
        }

        public Optional<BigDecimal> getBuildOfLongTermProvision() {
            return Optional.ofNullable(buildOfLongTermProvision);
        }

        @Override
        public BigDecimal sumOf() {
            return BigDecimals.sum(otherIncome, buildOfLongTermProvision);
        }

    }

    @AllArgsConstructor
    @Builder(toBuilder = true)
    @EqualsAndHashCode
    @ToString
    @NoArgsConstructor
    @Embeddable
    public static class CostOfGoodsAndServices implements Summable {

        private BigDecimal externalServices;

        public Optional<BigDecimal> getExternalServices() {
            return Optional.ofNullable(externalServices);
        }

        @Override
        public BigDecimal sumOf() {
            return BigDecimals.sum(externalServices);
        }

    }

    @AllArgsConstructor
    @Builder(toBuilder = true)
    @EqualsAndHashCode
    @ToString
    @NoArgsConstructor
    @Embeddable
    public static class OperatingExpenses implements Summable {

        private BigDecimal personnelExpenses;
        private BigDecimal generalAndAdministrativeExpenses;
        private BigDecimal depreciationAndImpairmentLossesOnTangibleAssets;
        private BigDecimal amortizationOnIntangibleAssets;
        private BigDecimal rentExpenses;

        public Optional<BigDecimal> getPersonnelExpenses() {
            return Optional.ofNullable(personnelExpenses);
        }

        public Optional<BigDecimal> getGeneralAndAdministrativeExpenses() {
            return Optional.ofNullable(generalAndAdministrativeExpenses);
        }

        public Optional<BigDecimal> getDepreciationAndImpairmentLossesOnTangibleAssets() {
            return Optional.ofNullable(depreciationAndImpairmentLossesOnTangibleAssets);
        }

        public Optional<BigDecimal> getAmortizationOnIntangibleAssets() {
            return Optional.ofNullable(amortizationOnIntangibleAssets);
        }

        public Optional<BigDecimal> getRentExpenses() {
            return Optional.ofNullable(rentExpenses);
        }

        @Override
        public BigDecimal sumOf() {
            return BigDecimals.sum(personnelExpenses, generalAndAdministrativeExpenses, depreciationAndImpairmentLossesOnTangibleAssets, amortizationOnIntangibleAssets, rentExpenses);
        }

    }

    @AllArgsConstructor
    @Builder(toBuilder = true)
    @EqualsAndHashCode
    @ToString
    @NoArgsConstructor
    @Embeddable
    public static class FinancialIncome implements Summable {

        private BigDecimal financialRevenues;
        private BigDecimal financialExpenses;
        private BigDecimal realisedGainsOnSaleOfCryptocurrencies;
        private BigDecimal stakingRewardsIncome;
        private BigDecimal netIncomeOptionsSale;

        public Optional<BigDecimal> getFinancialRevenues() {
            return Optional.ofNullable(financialRevenues);
        }

        public Optional<BigDecimal> getFinancialExpenses() {
            return Optional.ofNullable(financialExpenses);
        }

        public Optional<BigDecimal> getRealisedGainsOnSaleOfCryptocurrencies() {
            return Optional.ofNullable(realisedGainsOnSaleOfCryptocurrencies);
        }

        public Optional<BigDecimal> getStakingRewardsIncome() {
            return Optional.ofNullable(stakingRewardsIncome);
        }

        public Optional<BigDecimal> getNetIncomeOptionsSale() {
            return Optional.ofNullable(netIncomeOptionsSale);
        }

        @Override
        public BigDecimal sumOf() {
            return BigDecimals.sum(
                    financialRevenues,
                    financialExpenses,
                    realisedGainsOnSaleOfCryptocurrencies,
                    stakingRewardsIncome,
                    netIncomeOptionsSale
            );
        }

    }

    @AllArgsConstructor
    @Builder(toBuilder = true)
    @EqualsAndHashCode
    @ToString
    @NoArgsConstructor
    @Embeddable
    public static class ExtraordinaryIncome implements Summable {

        private BigDecimal extraordinaryExpenses;

        public Optional<BigDecimal> getExtraordinaryExpenses() {
            return Optional.ofNullable(extraordinaryExpenses);
        }

        @Override
        public BigDecimal sumOf() {
            return BigDecimals.sum(extraordinaryExpenses);
        }

    }

    @AllArgsConstructor
    @Builder(toBuilder = true)
    @EqualsAndHashCode
    @ToString
    @NoArgsConstructor
    @Embeddable
    public static class TaxExpenses implements Summable {

        private BigDecimal directTaxes;

        public Optional<BigDecimal> getDirectTaxes() {
            return Optional.ofNullable(directTaxes);
        }

        @Override
        public BigDecimal sumOf() {
            return BigDecimals.sum(directTaxes);
        }

    }

}
