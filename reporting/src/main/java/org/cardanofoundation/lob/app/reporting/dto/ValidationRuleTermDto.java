package org.cardanofoundation.lob.app.reporting.dto;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A term in a validation rule representing a field with an operation")
public class ValidationRuleTermDto {

    @Schema(description = "Field name this term refers to", example = "Total Assets")
    @NotNull(message = "Field name must not be null")
    private String fieldName;

    @Schema(description = "Operation to apply to this field", example = "ADD", allowableValues = {"ADD", "SUBTRACT"})
    @NotNull(message = "Operation must not be null")
    private String operation;
}
