package org.cardanofoundation.lob.app.csv_erp_adapter.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

import com.opencsv.bean.CsvBindByName;

@Data
public class TransactionLine {

    @CsvBindByName(column = "Transaction Number", required = true)
    @NotNull
    @NotBlank
    private String txNumber;
    @CsvBindByName(column = "Transaction Date", required = true)
    @NotNull
    @NotBlank
    private String date;
    @CsvBindByName(column = "Transaction Type", required = true)
    @NotNull
    @NotBlank
    private String type;
    @CsvBindByName(column = "Fx Rate", required = true)
    @NotNull
    @NotBlank
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
    @CsvBindByName(column = "Currency", required = true)
    @NotNull
    @NotBlank
    private String currency;

    @CsvBindByName(column = "VAT Rate")
    private String vatRate;
    @CsvBindByName(column = "VAT Code")
    private String vatCode;

    @CsvBindByName(column = "Cost Center Code")
    private String costCenterCode;
    @CsvBindByName(column = "Counterparty Code")
    private String counterPartyCode;
    @CsvBindByName(column = "CounterpartyName")
    private String counterPartyName;
}
