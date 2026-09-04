package org.cardanofoundation.lob.app.organisation.domain.request;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.opencsv.bean.CsvBindByName;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class AccountingRegimeUpdate {

    @Schema(example = "IFRS")
    @CsvBindByName(column = "Code")
    @NotNull(message = "Code is required")
    private String code;
    @Schema(example = "IFRS")
    @CsvBindByName(column = "Label")
    @NotNull(message = "Label is required")
    private String label;
    @CsvBindByName(column = "Active")
    @NotNull(message = "Active is required")
    private Boolean active;
}
