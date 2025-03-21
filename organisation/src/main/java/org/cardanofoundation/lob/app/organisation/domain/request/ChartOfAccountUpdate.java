package org.cardanofoundation.lob.app.organisation.domain.request;



import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.organisation.domain.entity.OpeningBalance;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ChartOfAccountUpdate {

    @Schema(example = "2203560100")
    private String customerCode;

    @Schema(example = "0000")
    private String eventRefCode;

    @Schema(example = "0000")
    private String refCode;

    @Schema(example = "description")
    private String name;

    @Schema(example = "3")
    private String subType;

    @Schema(example = "USD")
    private String currency;

    @Schema(example = "3")
    private String counterParty;

    @Schema(example = "1")
    private String type;

    @Schema(example = "2203560100")
    private String parentCustomerCode;

    @Schema(example = "true")
    private Boolean active;

    private OpeningBalance openingBalance;


}
