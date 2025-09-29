package org.cardanofoundation.lob.app.organisation.domain.csv;

import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import com.opencsv.bean.CsvBindByName;

@Getter
@Setter
@RequiredArgsConstructor
public class CostCenterUpdate {

    @CsvBindByName(column = "Customer code")
    @NotNull(message = "Customer Code is required")
    private String customerCode;
    @CsvBindByName(column = "Name")
    @NotNull(message = "Name is required")
    private String name;
    @CsvBindByName(column = "Parent customer code")
    private String parentCustomerCode;
    @CsvBindByName(column = "Active")
    @NotNull(message = "Active is required")
    private Boolean active;

}
