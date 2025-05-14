package org.cardanofoundation.lob.app.support.calc;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

public class MoreBigDecimal {

    private MoreBigDecimal() {
        // Utility class
    }

    public static BigDecimal zeroForNull(@Nullable BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }

        return value;
    }

}
