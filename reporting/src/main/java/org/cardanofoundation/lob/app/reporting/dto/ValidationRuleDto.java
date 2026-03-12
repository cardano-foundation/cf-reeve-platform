package org.cardanofoundation.lob.app.reporting.dto;

import java.util.List;
import java.util.Objects;

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
@Schema(description = "Validation rule for report template fields")
public class ValidationRuleDto {

    @Schema(description = "Rule name", example = "Assets Balance Check")
    @NotNull(message = "Rule name must not be null")
    private String name;

    @Schema(description = "Comparison operator", example = "EQUAL", allowableValues = {"GREATER_THAN_OR_EQUAL", "EQUAL", "LESS_THAN_OR_EQUAL"})
    @NotNull(message = "Comparison operator must not be null")
    private String operator;

    @Schema(description = "Whether the rule is active", example = "true", defaultValue = "true")
    @Builder.Default
    private boolean active = true;

    @Schema(description = "Terms on the left side of the comparison")
    @NotNull(message = "Left side terms must not be null")
    private List<ValidationRuleTermDto> leftSideTerms;

    @Schema(description = "Terms on the right side of the comparison")
    @NotNull(message = "Right side terms must not be null")
    private List<ValidationRuleTermDto> rightSideTerms;

    public int computeContentHash() {
        return Objects.hash(
                operator,
                leftSideTerms.stream().map(ValidationRuleTermDto::computeContentHash).toList(),
                rightSideTerms.stream().map(ValidationRuleTermDto::computeContentHash).toList()
        );
    }
}
