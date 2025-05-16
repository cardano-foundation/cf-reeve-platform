package org.cardanofoundation.lob.app.organisation.domain.csv;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import com.opencsv.bean.CsvBindByName;

@Getter
@Setter
@RequiredArgsConstructor
public class ProjectUpdate {

    @CsvBindByName(column = "customer code", required = true)
    private String customerCode;
    @CsvBindByName(column = "external customer code", required = true)
    private String externalCustomerCode;
    @CsvBindByName(column = "name", required = true)
    private String name;
    @CsvBindByName(column = "parent customer code")
    private String parentCustomerCode;
}
