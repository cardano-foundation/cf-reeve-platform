package org.cardanofoundation.lob.app.organisation.domain.request;

import static java.lang.Boolean.TRUE;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

import javax.annotation.Nullable;

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
public class VatUpdate {

    @Schema(example = "CH-N")
    @CsvBindByName(column = "Code")
    @NotNull(message = "Customer Code is required")
    private String customerCode;

    @Schema(example = "0000")
    @CsvBindByName(column = "Rate")
    @NotNull(message = "Rate is required")
    private BigDecimal rate;

    @Nullable
    @Schema(example = "IE")
    @CsvBindByName(column = "Country")
    private String countryCode;

    @Schema(example = "Example Vat code")
    @CsvBindByName(column = "Description")
    @NotNull(message = "Description is required")
    private String description;

    @CsvBindByName(column = "Active")
    private Boolean active = TRUE;
}
