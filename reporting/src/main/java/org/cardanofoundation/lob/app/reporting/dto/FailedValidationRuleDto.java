package org.cardanofoundation.lob.app.reporting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Details of a failed validation rule")
public class FailedValidationRuleDto {

    @Schema(description = "Validation rule ID", example = "123")
    private Long ruleId;

    @Schema(description = "Validation rule name", example = "Balance Sheet Equation")
    private String ruleName;

    @Schema(description = "Left side calculation result", example = "1000.00")
    private String leftSideResult;


    @Schema(description = "Operator used", example = "EQUAL")
    private String operator;

    @Schema(description = "Detailed error message", example = "Expected 1000.00 = 950.00, but they are not equal")
    private String errorMessage;
}
