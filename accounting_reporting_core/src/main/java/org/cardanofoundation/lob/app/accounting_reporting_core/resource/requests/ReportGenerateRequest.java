package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Builder;
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
@NoArgsConstructor
@Slf4j
@Builder
public class ReportGenerateRequest extends BaseRequest {

    @Schema(example = "INCOME_STATEMENT")
    private ReportType reportType;

    private IntervalType intervalType;

    @Schema(example = "2023")
    private short year;

    @Schema(example = "3")
    @Nullable
    private short period;

    @Schema(example = "false", description = "If true, the report will be generated in preview mode, it will take all valid transactions into account and not only the finalized ones.")
    @Builder.Default
    private boolean preview = false;

}
