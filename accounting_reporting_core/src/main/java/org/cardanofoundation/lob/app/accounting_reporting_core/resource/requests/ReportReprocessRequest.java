package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;


@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Slf4j
@Builder
public class ReportReprocessRequest extends BaseRequest {

    @Schema(example = "1234567", description = "The ID of the report to be reprocessed")
    private String reportId;

}
