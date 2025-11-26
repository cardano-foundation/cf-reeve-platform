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
public class TemplateCsvLine {

    @CsvBindByName(column = "Name")
    @NotNull(message = "Name is required")
    private String name;
    @CsvBindByName(column = "ReportType")
    @NotNull(message = "ReportType is required")
    private String reportType;
    @CsvBindByName(column = "Field Name")
    @NotNull(message = "Field Name is required")
    private String fieldName;
    @CsvBindByName(column = "Parent")
    private String parent = "";
    @CsvBindByName(column = "Mapped Types")
    private  String types = "";
    @CsvBindByName(column = "Accumulated")
    private Boolean accumulated = false;
    @CsvBindByName(column = "Accumulated Yearly")
    private Boolean accumulatedYearly = false;
    @CsvBindByName(column = "Accumulated Previous Year")
    private Boolean accumulatedPreviousYear = false;
    @CsvBindByName(column = "Negated")
    private Boolean negated = false;

}
