package org.cardanofoundation.lob.app.organisation.domain.csv;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import com.opencsv.bean.CsvBindByName;

@Getter
@Setter
@RequiredArgsConstructor
public class ReportTypeFieldUpdateCsv {

    @CsvBindByName(column = "Report Type", required = true)
    private String reportType;
    @CsvBindByName(column = "Report Type Field", required = true)
    private String reportTypeField;
    @CsvBindByName(column = "Sub Type", required = true)
    private String subType;

}
