package org.cardanofoundation.lob.app.organisation.domain.request;

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
    @CsvBindByName(column = "Customer Code", required = true)
    private String customerCode;
    @Schema(example = "ISO_4217:CHF")
    @CsvBindByName(column = "Currency ID", required = true)
    private String currencyId;
    @CsvBindByName(column = "Active")
    private boolean active = true;
}
