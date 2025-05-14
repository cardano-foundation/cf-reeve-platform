package org.cardanofoundation.lob.app.csv_erp_adapter.domain;

import lombok.Data;

import com.opencsv.bean.CsvBindByName;

@Data
public class TransactionLine {

    @CsvBindByName(column = "Transaction Number")
    private String txNumber;
    @CsvBindByName(column = "Transaction Date")
    private String date;
    @CsvBindByName(column = "Transaction Type")
    private String type;
    @CsvBindByName(column = "Fx Rate")
    private String fxRate;
    @CsvBindByName(column = "AmountLCY Debit")
    private String amountLCYDebit;
    @CsvBindByName(column = "AmountLCY Credit")
    private String amountLCYCredit;
    @CsvBindByName(column = "AmountFCY Debit")
    private String amountFCYDebit;
    @CsvBindByName(column = "AmountFCY Credit")
    private String amountFCYCredit;
    @CsvBindByName(column = "Debit Code")
    private String debitCode;
    @CsvBindByName(column = "Debit Name")
    private String debitName;
    @CsvBindByName(column = "Credit Code")
    private String creditCode;
    @CsvBindByName(column = "Credit Name")
    private String creditName;

    @CsvBindByName(column = "Project Code")
    private String projectCode;

    @CsvBindByName(column = "Document Name")
    private String documentNumber;
    @CsvBindByName(column = "Currency")
    private String currency;

    @CsvBindByName(column = "VAT Rate")
    private String vatRate;
    @CsvBindByName(column = "VAT Code")
    private String vatCode;

//    private String costCenterNumber;
//    private String costCenterName;
//    private String projectNumber;
//    private String projectName;
//    private String documentNumber;
//    private String currency;
//    private String vatRate;
//    private String vatCode;
//    private String vendorNumber;
//    private String vendorName;
}
