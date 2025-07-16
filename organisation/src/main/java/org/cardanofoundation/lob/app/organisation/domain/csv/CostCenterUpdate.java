package org.cardanofoundation.lob.app.organisation.domain.csv;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import com.opencsv.bean.CsvBindByName;

@Getter
@Setter
@RequiredArgsConstructor
public class CostCenterUpdate {

    @CsvBindByName(column = "Customer code", required = true)
    private String customerCode;
    @CsvBindByName(column = "Name", required = true)
    private String name;
    @CsvBindByName(column = "Parent customer code")
    private String parentCustomerCode;
    @CsvBindByName(column = "Active")
    private boolean active = true;

}
