package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Slf4j
@Data
public class ExtractionRequest extends BaseRequest {

    @Schema(example = "NETSUITE")
    private ExtractorType extractorType = ExtractorType.NETSUITE;

    @Schema(example = "2013-01-02")
    private String dateFrom = "";

    @Schema(example = "2024-05-01")
    private String dateTo = "";

    @ArraySchema(arraySchema = @Schema(example = "[\"FxRevaluation\",\"Journal\",\"CustomerPayment\"] ", implementation = TransactionType.class))
    private List<TransactionType> transactionType = List.of();

    @ArraySchema(arraySchema = @Schema(example = "[\"CARDCH565\",\"CARDHY777\",\"CARDCHRG159\",\"VENDBIL119\"] "))
    private List<String> transactionNumbers = List.of();

    @Schema(example = "A file for the extraction. E.g. a csv file")
    private MultipartFile file;

    @Schema(example = "A map for additional parameters for the extraction")
    private Map<String, Object> parameters = new HashMap<>();
}
