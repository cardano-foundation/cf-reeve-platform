package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports;

import java.math.BigDecimal;
import java.util.Optional;

import jakarta.persistence.Embeddable;

import javax.annotation.Nullable;

import lombok.*;

@AllArgsConstructor
@Builder(toBuilder = true)
@EqualsAndHashCode
@ToString
@NoArgsConstructor
@Embeddable
public class BalanceSheetData  {

    @Nullable
    private Assets assets;

    @Nullable
    private Liabilities liabilities;

    @Nullable
    private Capital capital;

    public Optional<Assets> getAssets() {
        return Optional.ofNullable(assets);
    }

    public Optional<Liabilities> getLiabilities() {
        return Optional.ofNullable(liabilities);
    }

    public Optional<Capital> getCapital() {
        return Optional.ofNullable(capital);
    }

    @AllArgsConstructor
    @Builder(toBuilder = true)
    @EqualsAndHashCode
    @ToString
    @NoArgsConstructor
    @Embeddable
    public static class Assets  {

        @Nullable
        private NonCurrentAssets nonCurrentAssets;

        @Nullable
        private CurrentAssets currentAssets;

        public Optional<NonCurrentAssets> getNonCurrentAssets() {
            return Optional.ofNullable(nonCurrentAssets);
        }

        public Optional<CurrentAssets> getCurrentAssets() {
            return Optional.ofNullable(currentAssets);
        }

        @AllArgsConstructor
        @Builder(toBuilder = true)
        @EqualsAndHashCode
        @ToString
        @NoArgsConstructor
        @Embeddable
        public static class NonCurrentAssets {

            @Nullable
            private BigDecimal tangibleAssets;

            @Nullable
            private BigDecimal intangibleAssets;

            @Nullable
            private BigDecimal investments;

            @Nullable
            private BigDecimal financialAssets;

            public Optional<BigDecimal> getTangibleAssets() {
                return Optional.ofNullable(tangibleAssets);
            }

            public Optional<BigDecimal> getIntangibleAssets() {
                return Optional.ofNullable(intangibleAssets);
            }

            public Optional<BigDecimal> getInvestments() {
                return Optional.ofNullable(investments);
            }

            public Optional<BigDecimal> getFinancialAssets() {
                return Optional.ofNullable(financialAssets);
            }

        }

        @AllArgsConstructor
        @Builder(toBuilder = true)
        @EqualsAndHashCode
        @ToString
        @NoArgsConstructor
        @Embeddable
        public static class CurrentAssets {

            @Nullable
            private BigDecimal prepaymentsAndOtherShortTermAssets;

            @Nullable
            private BigDecimal otherReceivables;

            @Nullable
            private BigDecimal cryptoAssets;

            @Nullable
            private BigDecimal cashAndCashEquivalents;

            public Optional<BigDecimal> getPrepaymentsAndOtherShortTermAssets() {
                return Optional.ofNullable(prepaymentsAndOtherShortTermAssets);
            }

            public Optional<BigDecimal> getOtherReceivables() {
                return Optional.ofNullable(otherReceivables);
            }

            public Optional<BigDecimal> getCryptoAssets() {
                return Optional.ofNullable(cryptoAssets);
            }

            public Optional<BigDecimal> getCashAndCashEquivalents() {
                return Optional.ofNullable(cashAndCashEquivalents);
            }

        }

    }

    @AllArgsConstructor
    @Builder(toBuilder = true)
    @EqualsAndHashCode
    @ToString
    @NoArgsConstructor
    @Embeddable
    public static class Liabilities {

        @Nullable
        private NonCurrentLiabilities nonCurrentLiabilities;

        @Nullable
        private CurrentLiabilities currentLiabilities;

        public Optional<NonCurrentLiabilities> getNonCurrentLiabilities() {
            return Optional.ofNullable(nonCurrentLiabilities);
        }

        public Optional<CurrentLiabilities> getCurrentLiabilities() {
            return Optional.ofNullable(currentLiabilities);
        }

        @AllArgsConstructor
        @Builder(toBuilder = true)
        @EqualsAndHashCode
        @ToString
        @NoArgsConstructor
        @Embeddable
        public static class NonCurrentLiabilities {

            @Nullable
            private BigDecimal provisions;

            public Optional<BigDecimal> getProvisions() {
                return Optional.ofNullable(provisions);
            }

        }

        @AllArgsConstructor
        @Builder(toBuilder = true)
        @EqualsAndHashCode
        @ToString
        @NoArgsConstructor
        @Embeddable
        public static class CurrentLiabilities {

            @Nullable
            private BigDecimal tradeAccountsPayables;

            @Nullable
            private BigDecimal otherShortTermLiabilities;

            @Nullable
            private BigDecimal accrualsAndShortTermProvisions;

            public Optional<BigDecimal> getTradeAccountsPayables() {
                return Optional.ofNullable(tradeAccountsPayables);
            }

            public Optional<BigDecimal> getOtherShortTermLiabilities() {
                return Optional.ofNullable(otherShortTermLiabilities);
            }

            public Optional<BigDecimal> getAccrualsAndShortTermProvisions() {
                return Optional.ofNullable(accrualsAndShortTermProvisions);
            }
        }

    }

    @AllArgsConstructor
    @Builder(toBuilder = true)
    @EqualsAndHashCode
    @ToString
    @NoArgsConstructor
    @Embeddable
    public static class Capital {

        @Nullable
        private BigDecimal capital;

        @Nullable
        private BigDecimal profitForTheYear;

        @Nullable
        private BigDecimal resultsCarriedForward;

        public Optional<BigDecimal> getCapital() {
            return Optional.ofNullable(capital);
        }

        public Optional<BigDecimal> getProfitForTheYear() {
            return Optional.ofNullable(profitForTheYear);
        }

        public Optional<BigDecimal> getResultsCarriedForward() {
            return Optional.ofNullable(resultsCarriedForward);
        }

    }

}
