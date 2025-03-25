package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Slf4j
public class ReportGenerateRequest {

    @Schema(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
    private String organisationID;

    @Schema(example = "INCOME_STATEMENT")
    private ReportType reportType;

    private IntervalType intervalType;

    @Schema(example = "2023")
    private short year;

    @Schema(example = "3")
    @Nullable
    private short period;

}
