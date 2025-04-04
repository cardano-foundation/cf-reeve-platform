package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
//@Builder todo: For testing
@NoArgsConstructor
@Slf4j
public class BalanceSheetRequest extends BaseRequest {

    private ReportType reportType;

    private IntervalType intervalType;

    @Schema(example = "2024")
    private short year;

    @Schema(example = "3")
    private short period;

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
}
