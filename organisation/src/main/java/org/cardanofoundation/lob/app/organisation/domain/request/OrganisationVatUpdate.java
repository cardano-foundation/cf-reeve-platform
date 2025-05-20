package org.cardanofoundation.lob.app.organisation.domain.request;

import static java.lang.Boolean.TRUE;

import java.math.BigDecimal;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class OrganisationVatUpdate {

    @Schema(example = "CH-N")
    private String customerCode;

    @Schema(example = "0000")
    private BigDecimal rate;

    @Nullable
    private String parentOrganisationVat;

    @Schema(example = "Example Vat code")
    private String description;

    private Boolean active = TRUE;
}
