package org.cardanofoundation.lob.app.organisation.domain.request;


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
    @CsvBindByName(column = "Debit Reference Code", required = true)
    private String debitReferenceCode;

    @Schema(example = "1111")
    @CsvBindByName(column = "Credit Reference Code", required = true)
    private String creditReferenceCode;

    @Schema(example = "Example reference code")
    @CsvBindByName(column = "Name", required = true)
    private String name;

    @CsvBindByName(column = "Active")
    private Boolean active = true;
}
