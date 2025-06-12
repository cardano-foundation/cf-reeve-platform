package org.cardanofoundation.lob.app.organisation.domain.request;



import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.opencsv.bean.CsvBindByName;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.organisation.domain.entity.OpeningBalance;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ChartOfAccountUpdate {

    @Schema(example = "2203560100")
    @CsvBindByName(column = "Customer Code", required = true)
    private String customerCode;

    @Schema(example = "0000")
    @CsvBindByName(column = "Event Reference Code")
    private String eventRefCode;

    @Schema(example = "0000")
    @CsvBindByName(column = "Reference Code", required = true)
    private String refCode;

    @Schema(example = "description")
    @CsvBindByName(column = "Name", required = true)
    private String name;

    @Schema(example = "3")
    @CsvBindByName(column = "Sub Type", required = true)
    private String subType;

    @Schema(example = "USD")
    @CsvBindByName(column = "Currency")
    private String currency;

    @Schema(example = "3")
    @CsvBindByName(column = "CounterParty")
    private String counterParty;

    @Schema(example = "1")
    @CsvBindByName(column = "Type", required = true)
    private String type;

    @Schema(example = "2203560100")
    @CsvBindByName(column = "Parent Customer Code")
    private String parentCustomerCode;

    @Schema(example = "true")
    @CsvBindByName(column = "Active", required = true)
    private Boolean active;

    private OpeningBalance openingBalance;


}
