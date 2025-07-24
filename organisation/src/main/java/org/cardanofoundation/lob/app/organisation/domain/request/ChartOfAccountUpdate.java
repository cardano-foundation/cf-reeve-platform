package org.cardanofoundation.lob.app.organisation.domain.request;



import jakarta.validation.constraints.NotNull;

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
    @CsvBindByName(column = "Customer Code")
    @NotNull(message = "Customer Code is required")
    private String customerCode;

    @Schema(example = "0000")
    @CsvBindByName(column = "Reference Code")
    @NotNull(message = "Reference Code is required")
    private String refCode;

    @Schema(example = "description")
    @CsvBindByName(column = "Name")
    @NotNull(message = "Name is required")
    private String name;

    @Schema(example = "3")
    @CsvBindByName(column = "Sub Type")
    @NotNull(message = "Sub Type is required")
    private String subType;

    @Schema(example = "USD")
    @CsvBindByName(column = "Currency")
    private String currency;

    @Schema(example = "3")
    @CsvBindByName(column = "CounterParty")
    private String counterParty;

    @Schema(example = "1")
    @CsvBindByName(column = "Type")
    @NotNull(message = "Type is required")
    private String type;

    @Schema(example = "2203560100")
    @CsvBindByName(column = "Parent Customer Code")
    private String parentCustomerCode;

    @Schema(example = "true")
    @CsvBindByName(column = "Active")
    private Boolean active = true;

    private OpeningBalance openingBalance;


}
