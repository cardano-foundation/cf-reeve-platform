package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
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
public class ReportPublishRequest extends BaseRequest {

    @Schema(example = "25acd91f465974740dc89f9f0f428235773c2385bb81eeca379bb821c86e089f")
    @NotBlank
    private String reportId;
}
