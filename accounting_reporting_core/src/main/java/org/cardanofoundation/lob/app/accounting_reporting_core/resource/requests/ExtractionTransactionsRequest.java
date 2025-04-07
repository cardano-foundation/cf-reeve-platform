package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;


import java.time.LocalDate;
import java.util.List;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ExtractionTransactionsRequest extends BaseRequest {

    @Schema(example = "2023-01-01")
    private LocalDate dateFrom;

    @Schema(example = "2023-31-01")
    private LocalDate dateTo;

    @ArraySchema(arraySchema = @Schema(example = "[\"2102110100\",\"2406210100\"]"))
    @Nullable
    private List<String> accountCode;

    @ArraySchema(arraySchema = @Schema(example = "[\"2\",\"3\"]"))
    @Nullable
    private List<String> accountType;

    @ArraySchema(arraySchema = @Schema(example = "[\"1\",\"4\"]"))
    @Nullable
    private List<String> accountSubType;

    @ArraySchema(arraySchema = @Schema(example = "[\"4300\",\"5400\"]"))
    @Nullable
    private List<String> costCenter;

    @ArraySchema(arraySchema = @Schema(example = "[\"AN 000001 2023\",\"CF 000001 2023\"]"))
    @Nullable
    private List<String> project;

}
