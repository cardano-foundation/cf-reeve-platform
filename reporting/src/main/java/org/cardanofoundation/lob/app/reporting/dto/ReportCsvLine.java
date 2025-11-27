package org.cardanofoundation.lob.app.reporting.dto;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.opencsv.bean.CsvBindByName;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ReportCsvLine {

    @CsvBindByName(column = "Template name")
    @NotNull(message = "TemplateName is required")
    private String templateName;
    @CsvBindByName(column = "Name")
    @NotNull(message = "Name is required")
    private String name;
    @CsvBindByName(column = "Interval type")
    @NotNull(message = "IntervalType is required")
    private String intervalType;
    @CsvBindByName(column = "Period")
    @NotNull(message = "Period is required")
    private Short period;
    @CsvBindByName(column = "Year")
    @NotNull(message = "Year is required")
    private Short year;
    @CsvBindByName(column = "Data mode")
    @NotNull(message = "DataMode is required")
    private String dataMode;
    @CsvBindByName(column = "Field name")
    private String field;
    @CsvBindByName(column = "Amount")
    private String amount;
}
