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
    @CsvBindByName(column = "Report Type")
    @NotNull(message = "Report Type is required")
    private String reportType;
    @CsvBindByName(column = "Generation Method")
    @NotNull(message = "Generation Method is required. Options are: Automatic or Manual")
    private String dataMode;
    // Displayed as "Standard"; stored in the template's accountingRegime field
    @CsvBindByName(column = "Standard")
    @NotNull(message = "Standard is required")
    private String standard;
    @CsvBindByName(column = "Active")
    @NotNull(message = "Active is required. Options are: true or false")
    private String active;
    @CsvBindByName(column = "Description")
    private String description = "";
    @CsvBindByName(column = "Field Name")
    @NotNull(message = "Field Name is required")
    private String fieldName;
    @CsvBindByName(column = "Parent")
    private String parent = "";
    @CsvBindByName(column = "Accounts")
    private  String accounts = "";
    // Date Range and Sign only drive Automatic generation; required for Automatic templates in CsvReportTemplateService
    @CsvBindByName(column = "Date Range")
    private String dateRange = "";
    @CsvBindByName(column = "Sign")
    private String sign = "";

}
