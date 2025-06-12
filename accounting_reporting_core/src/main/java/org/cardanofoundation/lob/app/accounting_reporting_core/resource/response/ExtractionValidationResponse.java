package org.cardanofoundation.lob.app.accounting_reporting_core.resource.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.zalando.problem.Problem;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class ExtractionValidationResponse {

    @Schema(description = "Correlation ID for tracking the request", example = "123e4567-e89b-12d3-a456-426614174000")
    private boolean valid;
    private List<Problem> errors;

}
