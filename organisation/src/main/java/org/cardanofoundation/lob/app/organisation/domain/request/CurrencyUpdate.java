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
public class CurrencyUpdate {

    @Schema(example = "CHF")
    @CsvBindByName(column = "Customer Code")
    @NotNull(message = "Customer Code is required")
    private String customerCode;
    @Schema(example = "ISO_4217:CHF")
    @CsvBindByName(column = "Currency ID")
    @NotNull(message = "Currency ID is required")
    private String currencyId;
    @CsvBindByName(column = "Active")
    @NotNull(message = "Active is required")
    private Boolean active;
}
