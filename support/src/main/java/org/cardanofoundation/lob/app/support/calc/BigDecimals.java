package org.cardanofoundation.lob.app.support.calc;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;

import lombok.experimental.UtilityClass;

@UtilityClass
public class BigDecimals {

    public BigDecimal sum(BigDecimal... values) {
        return Summable.sum(Arrays.stream(values)
                .filter(Objects::nonNull)
                .map(value -> (Summable) () -> value)
                .toArray(Summable[]::new));
    }

    public BigDecimal sum(Summable... values) {
        return Summable.sum(values);
    }

    public BigDecimal normalise(BigDecimal value) {
        if (value == null) {
            return null;
        }

        return value.setScale(8, BigDecimal.ROUND_HALF_UP).stripTrailingZeros();
    }

    public String normaliseEngineeringString(BigDecimal dec) {
        return normalise(dec).toEngineeringString();
    }

    public String normaliseString(BigDecimal dec) {
        return normalise(dec).toPlainString();
    }

}
