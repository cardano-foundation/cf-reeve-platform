package org.cardanofoundation.lob.app.organisation.domain.request;

import static java.lang.Boolean.TRUE;

import java.math.BigDecimal;

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
public class OrganisationVatUpdate {

    @Schema(example = "CH-N")
    @CsvBindByName(column = "Customer Code", required = true)
    private String customerCode;

    @Schema(example = "0000")
    @CsvBindByName(column = "Rate", required = true)
    private BigDecimal rate;

    @Nullable
    @CsvBindByName(column = "Parent Customer Code")
    private String parentOrganisationVat;

    @Schema(example = "Example Vat code")
    @CsvBindByName(column = "Description")
    private String description;

    @CsvBindByName(column = "Active")
    private Boolean active = TRUE;
}
