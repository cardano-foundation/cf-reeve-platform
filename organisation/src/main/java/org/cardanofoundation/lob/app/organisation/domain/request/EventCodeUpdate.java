package org.cardanofoundation.lob.app.organisation.domain.request;


import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.opencsv.bean.CsvBindByName;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class EventCodeUpdate {

    @Schema(example = "0000")
    @CsvBindByName(column = "Debit Reference Code")
    @NotNull(message = "Debit Reference Code is required")
    private String debitReferenceCode;

    @Schema(example = "1111")
    @CsvBindByName(column = "Credit Reference Code")
    @NotNull(message = "Credit Reference Code is required")
    private String creditReferenceCode;

    @Schema(example = "Example reference code")
    @CsvBindByName(column = "Name")
    @NotNull(message = "Name is required")
    private String name;
}
