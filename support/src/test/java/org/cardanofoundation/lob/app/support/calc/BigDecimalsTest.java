package org.cardanofoundation.lob.app.support.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BigDecimalsTest {

    @Test
    @DisplayName("Should normalize BigDecimal to scale 8 and strip trailing zeros")
    void testNormalise_withDifferentScales() {
        // Test case with more than 8 decimals
        BigDecimal input = new BigDecimal("123.456789123");
        BigDecimal expected = new BigDecimal("123.45678912");
        BigDecimal result = BigDecimals.normalise(input);
        assertThat(result).isEqualByComparingTo(expected);

        // Test case with less than 8 decimals
        input = new BigDecimal("123.450");
        expected = new BigDecimal("123.45");
        result = BigDecimals.normalise(input);
        assertThat(result).isEqualByComparingTo(expected);

        // Test case with exactly 8 decimals
        input = new BigDecimal("123.456789");
        expected = new BigDecimal("123.456789");
        result = BigDecimals.normalise(input);
        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("Should normalize BigDecimal and strip trailing zeros when the value is a whole number")
    void testNormalise_withWholeNumbers() {
        BigDecimal input = new BigDecimal("100");
        BigDecimal expected = new BigDecimal("100");
        BigDecimal result = BigDecimals.normalise(input);
        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("Should return BigDecimal with scale 8 and strip trailing zeros")
    void testNormalise_withExactScales() {
        BigDecimal input = new BigDecimal("123.456");
        BigDecimal expected = new BigDecimal("123.456");
        BigDecimal result = BigDecimals.normalise(input);
        assertThat(result).isEqualByComparingTo(expected);

        input = new BigDecimal("123.456789");
        expected = new BigDecimal("123.456789");
        result = BigDecimals.normalise(input);
        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("Should return null when BigDecimal is null")
    void testNormalise_withNullInput() {
        BigDecimal result = BigDecimals.normalise(null);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should return BigDecimal in Engineering String format with scale of 8")
    void testNormaliseEngineeringString_withValidBigDecimal() {
        BigDecimal input = new BigDecimal("123.456789123");
        String result = BigDecimals.normaliseEngineeringString(input);
        assertThat(result).isEqualTo("123.45678912");

        input = new BigDecimal("1000.00000");
        result = BigDecimals.normaliseEngineeringString(input);
        assertThat(result).isEqualTo("1E+3");

        input = new BigDecimal("0.00001");
        result = BigDecimals.normaliseEngineeringString(input);
        assertThat(result).isEqualTo("0.00001");
    }

    @Test
    @DisplayName("Should return BigDecimal in plan String format")
    void testNormalisePlainString_withValidBigDecimal() {
        BigDecimal input = new BigDecimal("123.456789123");
        String result = BigDecimals.normalisePlainString(input);
        assertThat(result).isEqualTo("123.45678912");

        input = new BigDecimal("1000.00000");
        result = BigDecimals.normalisePlainString(input);
        assertThat(result).isEqualTo("1000");

        input = new BigDecimal("0.00001");
        result = BigDecimals.normalisePlainString(input);
        assertThat(result).isEqualTo("0.00001");
    }

    @Test
    @DisplayName("Should return Engineering String representation for very small numbers")
    void testNormaliseEngineeringString_withSmallNumbers() {
        BigDecimal input = new BigDecimal("0.00000001");
        String result = BigDecimals.normaliseEngineeringString(input);
        assertThat(result).isEqualTo("10E-9");

        input = new BigDecimal("0.00000000001");
        result = BigDecimals.normaliseEngineeringString(input);
        assertThat(result).isEqualTo("0");
    }

    @Test
    public void testSumPositiveValues() {
        BigDecimal result = BigDecimals.sum(
                new BigDecimal("100.50"),
                new BigDecimal("200.75"),
                new BigDecimal("50.25")
        );

        Assertions.assertEquals(new BigDecimal("351.50"), result);
    }

    @Test
    public void testSumWithNullValues() {
        BigDecimal result = BigDecimals.sum(
                new BigDecimal("100.50"),
                null,
                new BigDecimal("50.25"),
                null
        );

        Assertions.assertEquals(new BigDecimal("150.75"), result);
    }

}
