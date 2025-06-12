package org.cardanofoundation.lob.app.accounting_reporting_core.resource.response;

import java.util.List;

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

    private boolean valid;
    private List<Problem> errors;

}
