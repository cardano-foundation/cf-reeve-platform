package org.cardanofoundation.lob.app.cf_netsuite_altavia_erp_connector.convertors;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import io.vavr.control.Either;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CostCenterConvertorTest {

    private CostCenterConvertor costCenterConvertor;

    @BeforeEach
    public void setup() {
        costCenterConvertor = new CostCenterConvertor();
    }

    @Test
    public void testCostCenter1() {
        assertThat(costCenterConvertor.apply("2001 All")).isEqualTo(Either.right("2001"));
    }

    @Test
    public void testCostCenter2() {
        assertThat(costCenterConvertor.apply("2001 All Executive")).isEqualTo(Either.right("2001"));
    }

    @Test
    public void testCostCenter3() {
        assertThat(costCenterConvertor.apply("200 All")).extracting(Either::isLeft).isEqualTo(true);
    }

    @Test
    public void testCostCenter4() {
        assertThat(costCenterConvertor.apply("200 All Ex")).extracting(Either::isLeft).isEqualTo(true);
    }

    @Test
    public void testCostCenter5() {
        assertThat(costCenterConvertor.apply("Enterprise Technology")).extracting(Either::isLeft).isEqualTo(true);
    }

}
