package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report;

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

    @CsvBindByName(column = "Report")
    @NotNull(message = "Report is required")
    private String report;
    @CsvBindByName(column = "Period")
    @NotNull(message = "Period is required")
    private String period;
    @CsvBindByName(column = "Year")
    @NotNull(message = "Year is required")
    private Short year;
    @CsvBindByName(column = "Interval Type")
    @NotNull(message = "Interval Type is required")
    private String intervalType;
    @CsvBindByName(column = "Field")
    @NotNull(message = "Field is required")
    private String field;
    @CsvBindByName(column = "Amount")
    @NotNull(message = "Amount is required")
    private Double amount;
}
