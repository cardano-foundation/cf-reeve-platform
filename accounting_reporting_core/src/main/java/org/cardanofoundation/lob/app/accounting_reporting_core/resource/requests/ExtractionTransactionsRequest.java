package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ExtractionTransactionsRequest extends BaseRequest {

    @Schema(example = "2023-01-01")
    private LocalDate dateFrom;

    @Schema(example = "2023-31-01")
    private LocalDate dateTo;

    @ArraySchema(arraySchema = @Schema(example = "[\"2102110100\",\"2406210100\"]"))
    @Nullable
    private List<String> accountCode;

    @ArraySchema(arraySchema = @Schema(example = "[\"4300\",\"5400\"]"))
    @Nullable
    private List<String> costCenter;

    @ArraySchema(arraySchema = @Schema(example = "[\"AN 000001 2023\",\"CF 000001 2023\"]"))
    @Nullable
    private List<String> project;

    @ArraySchema(arraySchema = @Schema(example = "[\"JOURNAL\",\"VendorBill\"]"))
    @Nullable
    private List<TransactionType> transactionType;

    @Schema(example = "abc123hashfromblockchain")
    @Nullable
    private String blockchainHash;
    @Schema(example = "JOUNRAL123")
    @Nullable
    private String transactionNumber;
    @Schema(example = "DOC123")
    @Nullable
    private String documentNumber;
    @ArraySchema(arraySchema = @Schema(example = "[\"USD\",\"EUR\"]"))
    @Nullable
    private List<String> currencys;
    @Schema(example = "100.00")
    @Nullable
    private BigDecimal minFcy;
    @Schema(example = "1000.00")
    @Nullable
    private BigDecimal maxFcy;
    @Schema(example = "100.00")
    @Nullable
    private BigDecimal minLcy;
    @Schema(example = "1000.00")
    @Nullable
    private BigDecimal maxLcy;
    @ArraySchema(arraySchema = @Schema(example = "[\"VAT21\",\"VAT7\"]"))
    @Nullable
    private List<String> vatCodes;
    @Schema(example = "CUST123")
    @Nullable
    private String counterPartyId;
    @Schema(example = "Example Corp")
    @Nullable
    private String counterPartyName;
    @ArraySchema(arraySchema = @Schema(example = "[\"EMPLOYEE\",\"VENDOR\",\"DONOR\",\"CLIENT\"]"))
    @Nullable
    private List<Counterparty.Type> counterPartyTypes;
    @ArraySchema(arraySchema = @Schema(example = "[\"EVENT1\",\"EVENT2\"]"))
    @Nullable
    private List<String> eventCodes;
    @Schema(example = "true")
    @Nullable
    private Boolean reconciled;
    @ArraySchema(arraySchema = @Schema(example = "[\"4300\",\"4400\"]"))
    @Nullable
    private List<String> parentCostcenters;
    @ArraySchema(arraySchema = @Schema(example = "[\"AN 000001 2023\",\"AN 000002 2023\"]"))
    @Nullable
    private List<String> parentProjects;
    @ArraySchema(arraySchema = @Schema(example = "[\"2102110100\",\"2406210100\"]"))
    @Nullable
    private List<String> accountCodesDebit;
    @ArraySchema(arraySchema = @Schema(example = "[\"2102110100\",\"2406210100\"]"))
    @Nullable
    private List<String> accountCodesCredit;

}
