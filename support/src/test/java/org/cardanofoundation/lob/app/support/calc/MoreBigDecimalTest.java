package org.cardanofoundation.lob.app.support.calc;

import java.math.BigDecimal;

import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;


@ExtendWith(MockitoExtension.class)
class MoreBigDecimalTest {

    @Test
    void testZeroForNull() {
        Assertions.assertEquals(BigDecimal.ZERO, MoreBigDecimal.zeroForNull(null));
        Assertions.assertEquals(BigDecimal.ONE, MoreBigDecimal.zeroForNull(BigDecimal.ONE));
    }
}
