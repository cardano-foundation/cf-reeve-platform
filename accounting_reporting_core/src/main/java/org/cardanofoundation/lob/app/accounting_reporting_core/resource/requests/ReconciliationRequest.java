package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
//@Builder todo: For testing
@NoArgsConstructor
@Slf4j
public class ReconciliationRequest extends BaseRequest {

    @Schema(example = "NETSUITE")
    private ExtractorType extractorType = ExtractorType.NETSUITE;

    @Schema(example = "2014-01-01")
    private LocalDate dateFrom;

    @Schema(example = "2024-07-31")
    private LocalDate dateTo;

    @Schema(example = "A file for the extraction. E.g. a csv file")
    private MultipartFile file;

    @Schema(example = "A map for additional parameters for the extraction")
    private Map<String, Object> parameters = new HashMap<>();

    }
