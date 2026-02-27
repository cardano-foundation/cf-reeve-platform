package org.cardanofoundation.lob.app.reporting.dto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.reporting.model.enums.ReportFieldDateRange;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Report template field definition")
public class ReportTemplateFieldDto {

    @Schema(description = "Unique field ID", example = "1")
    private Long id;

    @Schema(description = "Field name", example = "Total Revenue")
    @NotNull(message = "Field name must not be null")
    private String fieldName;

    @Schema(description = """
            Date range of the specific field. Options are: PERIOD,ACCUMULATED_START_TO_PERIOD_END,ACCUMULATED_YEAR_TO_PERIOD_END,ACCUMULATED_PREVIOUS_YEAR_TO_PREVIOUS_YEAR_END,ACCUMULATED_PREVIOUS_YEAR_TO_PERIOD_END
            """, example = "PERIOD",  nullable = true)
    private ReportFieldDateRange dateRange;

    @Schema(description = "Whether the value should be negated (for expenses)", example = "false", defaultValue = "false")
    private boolean negated;

    @Schema(description = "List of chart of account customer codes to map to this field. Only applicable if the template type is SYSTEM.", example = "[1101100100, 1101100101]", nullable = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Set<String> accounts = new HashSet<>();

    @Schema(description = "Child fields forming a hierarchical structure", nullable = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<ReportTemplateFieldDto> childFields = new ArrayList<>();

    /**
     * Computes a hash based on: childFields, fieldName, dateRange, and negated.
     * Used for quick comparison with entities to detect changes.
     */
    public int computeContentHash() {
        return Objects.hash(
                hashChildFields(),
                fieldName,
                dateRange,
                negated
        );
    }

    /**
     * Helper method to compute hash of child fields recursively.
     */
    private int hashChildFields() {
        if (childFields == null || childFields.isEmpty()) {
            return 0;
        }
        int hash = 1;
        for (ReportTemplateFieldDto child : childFields) {
            hash = 31 * hash + child.computeContentHash();
        }
        return hash;
    }
}
