package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus;
import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class SearchRequest extends BaseRequest {

    @ArraySchema(arraySchema = @Schema(example = "[\"FAILED\",\"VALIDATED\"]", implementation = TxValidationStatus.class))
    private List<TxValidationStatus> status = List.of();

    @ArraySchema(arraySchema = @Schema(example = "[\"CardRefund\",\"Journal\",\"ExpenseReport\"]", implementation = TransactionType.class))
    private List<TransactionType> transactionType = List.of();

    @Schema(example = "0")
    private Integer page = 0;
    @Schema(example = "10")
    private Integer size = 10;

}
